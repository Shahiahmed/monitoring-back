package com.example.monitoring.security;

import com.example.monitoring.repository.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

@Component
@RequiredArgsConstructor
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    @Override
    protected void doFilterInternal(
            @NonNull HttpServletRequest request,
            @NonNull HttpServletResponse response,
            @NonNull FilterChain filterChain
    ) throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header == null || !header.startsWith(BEARER_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        String raw = header.substring(BEARER_PREFIX.length()).trim();
        if (raw.isEmpty()) {
            filterChain.doFilter(request, response);
            return;
        }

        try {
            Long userId = jwtService.parseUserId(raw);
            userRepository.findById(userId).ifPresent(user -> {
                if (!Boolean.TRUE.equals(user.getIsActive())) {
                    return;
                }
                MonitoringUserPrincipal principal = MonitoringUserPrincipal.from(user);
                var auth = new UsernamePasswordAuthenticationToken(
                        principal,
                        null,
                        principal.getAuthorities()
                );
                auth.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(auth);
            });
        } catch (Exception ignored) {
            // невалидный или просроченный JWT — контекст не заполняем, дальше вернётся 401
        }

        filterChain.doFilter(request, response);
    }
}
