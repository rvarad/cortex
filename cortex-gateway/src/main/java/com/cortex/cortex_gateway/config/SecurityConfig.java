package com.cortex.cortex_gateway.config;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.configuration.WebSecurityCustomizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
@EnableWebSecurity
public class SecurityConfig {

  // static {
  // // Ensure security context is propagated to async threads (needed for SSE
  // relay)
  // org.springframework.security.core.context.SecurityContextHolder.setStrategyName(
  // org.springframework.security.core.context.SecurityContextHolder.MODE_INHERITABLETHREADLOCAL);
  // }

  @Value("${app.cors.allowed-origins}")
  private String allowedOrigins;

  @Bean
  public WebSecurityCustomizer webSecurityCustomizer() {
    return (web) -> web.ignoring()
        .requestMatchers("/api/v1/webhook/**");
  }

  @Bean
  public SecurityFilterChain filterChain(HttpSecurity httpSecurity) throws Exception {
    return httpSecurity
        .authorizeHttpRequests(auth -> auth
            .requestMatchers("/api/v1/webhook/**").permitAll()
            .requestMatchers("/actuator/**").permitAll()
            .requestMatchers("/auth/**").permitAll()
            .anyRequest().authenticated())
        .oauth2Login(oauth -> oauth
            .defaultSuccessUrl(allowedOrigins + "/dashboard", true))
        .logout(logout -> logout
            .logoutUrl("/auth/logout")
            .logoutSuccessUrl(allowedOrigins)
            .invalidateHttpSession(true)
            .deleteCookies("SESSION"))
        .csrf(csrf -> csrf.disable())
        .cors(cors -> cors.configurationSource(corsConfigSource()))
        .build();
  }

  private CorsConfigurationSource corsConfigSource() {
    CorsConfiguration config = new CorsConfiguration();
    config.setAllowedOriginPatterns(List.of(allowedOrigins));
    config.setAllowedMethods(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));
    config.setAllowedHeaders(List.of("*"));
    config.setAllowCredentials(true);

    UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
    source.registerCorsConfiguration("/**", config);

    return source;
  }
}