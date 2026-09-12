package com.claw.service;

import com.claw.dto.AuthResponse;
import com.claw.dto.LoginRequest;
import com.claw.dto.RegisterRequest;
import com.claw.entity.UserCredentialEntity;
import com.claw.entity.UserEntity;
import com.claw.entity.UserLoginSessionEntity;
import com.claw.repository.UserCredentialRepository;
import com.claw.repository.UserLoginSessionRepository;
import com.claw.repository.UserRepository;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;

@Service
public class AuthService {
    private static final String PASSWORD_LOGIN = "password";
    private static final int ITERATIONS = 210_000;
    private static final long SESSION_SECONDS = 30L * 24 * 60 * 60;
    private final UserRepository userRepository;
    private final UserCredentialRepository credentialRepository;
    private final UserLoginSessionRepository sessionRepository;
    private final SecureRandom random = new SecureRandom();

    public AuthService(UserRepository userRepository, UserCredentialRepository credentialRepository,
                       UserLoginSessionRepository sessionRepository) {
        this.userRepository = userRepository;
        this.credentialRepository = credentialRepository;
        this.sessionRepository = sessionRepository;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request, String clientType) {
        String username = normalize(request.username());
        if (userRepository.findByUsername(username).isPresent()) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username already exists");
        }
        UserEntity user = new UserEntity();
        user.setUsername(username);
        user.setDisplayName(request.displayName() == null || request.displayName().isBlank()
                ? username : request.displayName().trim());
        user = userRepository.save(user);
        UserCredentialEntity credential = new UserCredentialEntity();
        credential.setUserId(user.getId());
        credential.setLoginType(PASSWORD_LOGIN);
        credential.setCredential(hashPassword(request.password()));
        credentialRepository.save(credential);
        return createSession(user, clientType);
    }

    @Transactional
    public AuthResponse login(LoginRequest request, String clientType) {
        UserEntity user = userRepository.findByUsername(normalize(request.username()))
                .orElseThrow(this::invalidCredentials);
        UserCredentialEntity credential = credentialRepository
                .findFirstByUserIdAndLoginTypeAndEnabledTrue(user.getId(), PASSWORD_LOGIN)
                .orElseThrow(this::invalidCredentials);
        if (!verifyPassword(request.password(), credential.getCredential())) throw invalidCredentials();
        return createSession(user, clientType);
    }

    @Transactional
    public Optional<AuthenticatedUser> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        String tokenId = tokenId(rawToken);
        Optional<UserLoginSessionEntity> found = sessionRepository.findByTokenId(tokenId);
        if (found.isEmpty() || found.get().getExpiresAt().isBefore(LocalDateTime.now())) {
            if (found.isPresent()) sessionRepository.deleteByTokenId(tokenId);
            return Optional.empty();
        }
        return userRepository.findById(found.get().getUserId())
                .filter(user -> "active".equalsIgnoreCase(user.getStatus()))
                .map(user -> new AuthenticatedUser(user.getId(), user.getUsername(), user.getDisplayName(), tokenId));
    }

    @Transactional
    public void logout(AuthenticatedUser user) {
        if (user != null) sessionRepository.deleteByTokenId(user.tokenId());
    }

    private AuthResponse createSession(UserEntity user, String clientType) {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        String token = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        UserLoginSessionEntity session = new UserLoginSessionEntity();
        session.setUserId(user.getId());
        session.setTokenId(tokenId(token));
        session.setClientType(clientType == null || clientType.isBlank() ? "desktop" : clientType.trim());
        session.setExpiresAt(LocalDateTime.now().plusSeconds(SESSION_SECONDS));
        sessionRepository.save(session);
        return new AuthResponse(token, SESSION_SECONDS,
                new AuthResponse.UserResponse(user.getId(), user.getUsername(), user.getDisplayName()));
    }

    private String normalize(String value) { return value == null ? "" : value.trim().toLowerCase(Locale.ROOT); }

    private ResponseStatusException invalidCredentials() {
        return new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid username or password");
    }

    private String hashPassword(String password) {
        try {
            byte[] salt = new byte[16]; random.nextBytes(salt);
            return "pbkdf2$" + ITERATIONS + "$" + Base64.getEncoder().encodeToString(salt) + "$"
                    + Base64.getEncoder().encodeToString(derive(password, salt, ITERATIONS));
        } catch (Exception e) { throw new IllegalStateException("password hashing failed", e); }
    }

    private boolean verifyPassword(String password, String encoded) {
        try {
            String[] parts = encoded == null ? new String[0] : encoded.split("\\$", -1);
            if (parts.length != 4 || !"pbkdf2".equals(parts[0])) return false;
            byte[] salt = Base64.getDecoder().decode(parts[2]);
            byte[] expected = Base64.getDecoder().decode(parts[3]);
            return MessageDigest.isEqual(expected, derive(password, salt, Integer.parseInt(parts[1])));
        } catch (Exception e) { return false; }
    }

    private byte[] derive(String password, byte[] salt, int iterations) throws Exception {
        PBEKeySpec spec = new PBEKeySpec(password.toCharArray(), salt, iterations, 256);
        try { return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(); }
        finally { spec.clearPassword(); }
    }

    private String tokenId(String token) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(token.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("token hashing failed", e); }
    }
}
