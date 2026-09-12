package com.claw.controller;

import com.claw.dto.AuthResponse;
import com.claw.dto.LoginRequest;
import com.claw.dto.RegisterRequest;
import com.claw.service.AuthService;
import com.claw.service.AuthenticatedUser;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {
    public static final String USER_ATTRIBUTE = AuthController.class.getName() + ".user";
    private final AuthService authService;
    public AuthController(AuthService authService) { this.authService = authService; }
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("authentication", true, "version", 1);
    }
    @PostMapping("/register")
    public AuthResponse register(@Valid @RequestBody RegisterRequest request) { return authService.register(request, "desktop"); }
    @PostMapping("/login")
    public AuthResponse login(@Valid @RequestBody LoginRequest request) { return authService.login(request, "desktop"); }
    @GetMapping("/me")
    public AuthResponse.UserResponse me(HttpServletRequest request) {
        AuthenticatedUser user = (AuthenticatedUser) request.getAttribute(USER_ATTRIBUTE);
        return new AuthResponse.UserResponse(user.userId(), user.username(), user.displayName());
    }
    @PostMapping("/logout")
    public void logout(HttpServletRequest request) { authService.logout((AuthenticatedUser) request.getAttribute(USER_ATTRIBUTE)); }
}
