package com.autoreg.auth;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.context.SecurityContextRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/auth")
@RequiredArgsConstructor
public class AuthController {

    /** IP 별로 10번 틀리면 10분 잠근다 */
    static final int MAX_FAILURES = 10;
    static final Duration LOCK = Duration.ofMinutes(10);

    private final AuthenticationManager auth;
    private final SecurityContextRepository contexts;
    private final Map<String, Failures> failures = new ConcurrentHashMap<>();

    public record LoginRequest(@NotBlank String username, @NotBlank String password) {}

    public record Me(boolean authenticated, String username) {}

    private record Failures(int count, Instant since) {}

    @PostMapping("/login")
    public ResponseEntity<Me> login(@Valid @RequestBody LoginRequest req, HttpServletRequest request,
            HttpServletResponse response) {
        String ip = clientIp(request);
        Failures f = failures.get(ip);
        if (f != null && f.count() >= MAX_FAILURES && f.since().plus(LOCK).isAfter(Instant.now())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS).build();
        }
        try {
            Authentication a = auth.authenticate(new UsernamePasswordAuthenticationToken(req.username(), req.password()));
            if (request.getSession(false) != null) {
                request.changeSessionId(); // 세션 고정 방지
            }
            SecurityContext ctx = SecurityContextHolder.createEmptyContext();
            ctx.setAuthentication(a);
            SecurityContextHolder.setContext(ctx);
            contexts.saveContext(ctx, request, response);
            failures.remove(ip);
            return ResponseEntity.ok(new Me(true, a.getName()));
        } catch (AuthenticationException e) {
            failures.merge(ip, new Failures(1, Instant.now()),
                    (old, one) -> old.since().plus(LOCK).isBefore(Instant.now()) ? one : new Failures(old.count() + 1, old.since()));
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
    }

    @GetMapping("/me")
    public Me me(Authentication a) {
        return a == null || !a.isAuthenticated() ? new Me(false, null) : new Me(true, a.getName());
    }

    private static String clientIp(HttpServletRequest r) {
        String fwd = r.getHeader("X-Real-IP");
        return fwd != null && !fwd.isBlank() ? fwd : r.getRemoteAddr();
    }
}
