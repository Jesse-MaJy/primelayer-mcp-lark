package com.larkconnect.agent.admin;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.common.ApiResponse;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
public class AdminTokenFilter extends OncePerRequestFilter {
    private final AdminTokenService tokenService;
    private final ObjectMapper objectMapper;

    public AdminTokenFilter(AdminTokenService tokenService, ObjectMapper objectMapper) {
        this.tokenService = tokenService;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/admin") || path.equals("/api/admin/login")) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        String token = header != null && header.startsWith("Bearer ") ? header.substring(7) : null;
        AdminPrincipal principal = tokenService.verify(token);
        if (principal == null) {
            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
            objectMapper.writeValue(response.getWriter(), ApiResponse.error("Unauthorized"));
            return;
        }
        try {
            AdminContext.set(principal);
            chain.doFilter(request, response);
        } finally {
            AdminContext.clear();
        }
    }
}
