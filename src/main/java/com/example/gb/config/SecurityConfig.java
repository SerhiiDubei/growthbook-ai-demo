package com.example.gb.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AntPathRequestMatcher;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

    /**
     * Глобальна CORS конфігурація — дозволяє зовнішнім сайтам
     * викликати /api/gb/dom-inventory, /api/gb/track та інші публічні endpoints.
     * origins = "*" достатньо для демо; для продакшну замінити на список дозволених доменів.
     */
    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration config = new CorsConfiguration();
        config.setAllowedOriginPatterns(List.of("*"));
        config.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        config.setAllowedHeaders(List.of("*"));
        config.setAllowCredentials(false);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", config);
        return source;
    }

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .csrf(csrf -> csrf.ignoringRequestMatchers(
                        new AntPathRequestMatcher("/api/gb/upsert-feature", "POST"),
                        new AntPathRequestMatcher("/api/gb/preview", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-inventory", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-registry/sync", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-registry/map", "GET"),
                        new AntPathRequestMatcher("/bridge/**"),
                        new AntPathRequestMatcher("/api/gb/track", "POST"),
                        new AntPathRequestMatcher("/api/gb/dom-events", "POST"),
                        new AntPathRequestMatcher("/api/ai/**"),
                        new AntPathRequestMatcher("/api/experiments/**")
                ))
                .authorizeHttpRequests(reg -> reg.anyRequest().permitAll())
                .httpBasic(b -> b.disable())
                .formLogin(f -> f.disable());

        return http.build();
    }
}
