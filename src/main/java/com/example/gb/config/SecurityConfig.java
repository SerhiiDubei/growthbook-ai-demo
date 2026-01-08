package com.example.gb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                // Вимикаємо CSRF тільки для наших REST-ендпоїнтів консолі
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        new AntPathRequestMatcher("/api/gb/upsert-feature", "POST"),
                        new AntPathRequestMatcher("/api/gb/preview", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-inventory", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-registry/sync", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-registry/map", "GET"),
                        new AntPathRequestMatcher("/bridge/**"),
                        new AntPathRequestMatcher("/api/gb/track", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-events", "POST"),
                        new AntPathRequestMatcher("/api/ai/**")

                ))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());

        return http.build();
    }
}
