package com.autoreg.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.ProviderManager;
import org.springframework.security.authentication.dao.DaoAuthenticationProvider;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.HttpStatusEntryPoint;
import org.springframework.security.web.context.HttpSessionSecurityContextRepository;
import org.springframework.security.web.context.SecurityContextRepository;

/**
 * 사용자는 1명이다. 계정은 환경변수(AUTOREG_ADMIN_USER / AUTOREG_ADMIN_PASSWORD)로만 받는다.
 * 세션 쿠키 인증 + SPA 용 CSRF(XSRF-TOKEN 쿠키 → X-XSRF-TOKEN 헤더).
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain api(HttpSecurity http, SecurityContextRepository contexts) throws Exception {
        http
                .authorizeHttpRequests(a -> a
                        .requestMatchers("/api/auth/login", "/api/auth/me", "/actuator/health", "/actuator/health/**").permitAll()
                        .anyRequest().authenticated())
                .csrf(c -> c.spa().csrfTokenRepository(org.springframework.security.web.csrf.CookieCsrfTokenRepository.withHttpOnlyFalse()))
                .addFilterAfter(new CsrfCookieFilter(), org.springframework.security.web.authentication.www.BasicAuthenticationFilter.class)
                .securityContext(s -> s.securityContextRepository(contexts))
                .exceptionHandling(e -> e.authenticationEntryPoint(new HttpStatusEntryPoint(HttpStatus.UNAUTHORIZED)))
                .formLogin(f -> f.disable())
                .httpBasic(b -> b.disable())
                .logout(l -> l.logoutUrl("/api/auth/logout")
                        .logoutSuccessHandler((req, res, auth) -> res.setStatus(204)));
        return http.build();
    }

    @Bean
    SecurityContextRepository securityContextRepository() {
        return new HttpSessionSecurityContextRepository();
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        return PasswordEncoderFactories.createDelegatingPasswordEncoder();
    }

    @Bean
    UserDetailsService users(@Value("${autoreg.admin.user:}") String user,
            @Value("${autoreg.admin.password:}") String password, PasswordEncoder encoder) {
        if (user.isBlank() || password.length() < 10) {
            throw new IllegalStateException("AUTOREG_ADMIN_USER 와 10자 이상의 AUTOREG_ADMIN_PASSWORD 가 필요합니다");
        }
        return new InMemoryUserDetailsManager(User.withUsername(user).password(encoder.encode(password)).roles("ADMIN").build());
    }

    @Bean
    AuthenticationManager authenticationManager(UserDetailsService users, PasswordEncoder encoder) {
        DaoAuthenticationProvider provider = new DaoAuthenticationProvider(users);
        provider.setPasswordEncoder(encoder);
        return new ProviderManager(provider);
    }
}
