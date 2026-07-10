package com.ozimos.auth.security;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.config.Customizer;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http,
                                           JwtDecoder jwtDecoder,
                                           UserDetailsService userDetailsService,
                                           PasswordEncoder passwordEncoder) throws Exception {
        http
            .csrf(csrf -> csrf.disable())
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> jwt.decoder(jwtDecoder)))
            .userDetailsService(userDetailsService)
            .authorizeHttpRequests(authz -> authz
                .requestMatchers("/api/auth/login", "/api/auth/register", "/api/auth/verify", "/api/auth/forgot-password", "/actuator/health").permitAll()
                .requestMatchers("/api/auth/refresh", "/api/auth/reset-password").permitAll()
                .requestMatchers("/actuator/**").permitAll()
                .anyRequest().authenticated());

        return http.build();
    }
}