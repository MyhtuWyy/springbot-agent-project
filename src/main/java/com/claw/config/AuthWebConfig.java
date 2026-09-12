package com.claw.config;

import com.claw.controller.AuthController;
import com.claw.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;
import org.springframework.http.HttpMethod;

@Configuration
public class AuthWebConfig implements WebMvcConfigurer {
    private final AuthService authService;
    public AuthWebConfig(AuthService authService) { this.authService = authService; }
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new HandlerInterceptor() {
            @Override
            public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler) {
                // CORS preflight requests intentionally do not carry credentials.
                // Let Spring's CORS handling answer OPTIONS before auth is checked.
                if (HttpMethod.OPTIONS.matches(request.getMethod())) {
                    return true;
                }
                String header = request.getHeader("Authorization");
                String token = header != null && header.regionMatches(true, 0, "Bearer ", 0, 7)
                        ? header.substring(7).trim() : null;
                return authService.authenticate(token).map(user -> {
                    request.setAttribute(AuthController.USER_ATTRIBUTE, user); return true;
                }).orElseGet(() -> { response.setStatus(HttpServletResponse.SC_UNAUTHORIZED); return false; });
            }
        }).addPathPatterns("/api/auth/me", "/api/auth/logout", "/api/desktop/**", "/api/skills/**", "/api/settings/**")
                .excludePathPatterns("/api/auth/login", "/api/auth/register");
    }
}
