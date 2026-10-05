package com.autoreg.intake;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * /api/intake/** 는 세션이 아니라 확장 토큰(Authorization: Bearer ar_...)으로 인증한다.
 * 토큰 권한(ROLE_INTAKE)으로는 intake API 만 부를 수 있다. 빈으로 등록하지 않는다 (서블릿 필터로 두 번 걸리지 않게).
 */
public class IntakeTokenFilter extends OncePerRequestFilter {

    private final IntakeTokenService tokens;

    public IntakeTokenFilter(IntakeTokenService tokens) {
        this.tokens = tokens;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/api/intake/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse res, FilterChain chain)
            throws ServletException, IOException {
        String h = req.getHeader("Authorization");
        if (h != null && h.startsWith("Bearer ")) {
            // 새 컨텍스트를 만든다. 세션에서 불러온 컨텍스트 객체를 고치면 그 세션(관리자 로그인)이 토큰 권한으로 바뀐다
            tokens.authenticate(h.substring(7).trim()).ifPresent(name -> {
                var ctx = SecurityContextHolder.createEmptyContext();
                ctx.setAuthentication(UsernamePasswordAuthenticationToken.authenticated("intake:" + name, null,
                        List.of(new SimpleGrantedAuthority("ROLE_INTAKE"))));
                SecurityContextHolder.setContext(ctx);
            });
        }
        chain.doFilter(req, res);
    }
}
