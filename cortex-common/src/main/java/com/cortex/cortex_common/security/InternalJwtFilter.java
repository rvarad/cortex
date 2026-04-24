package com.cortex.cortex_common.security;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.filter.OncePerRequestFilter;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

public class InternalJwtFilter extends OncePerRequestFilter {

  @Value("${app.jwt.secret}")
  private String secret;

  @Override
  protected void doFilterInternal(HttpServletRequest request,
      HttpServletResponse response,
      FilterChain chain) throws ServletException, IOException {
    String header = request.getHeader("Authorization");

    if (header == null || !header.startsWith("Bearer ")) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.getWriter().write("{\"error\": \"Missing or invalid Authorization header\"}");
      return;
    }

    try {
      Claims claims = Jwts.parser()
          .verifyWith(Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8)))
          .build()
          .parseSignedClaims(header.substring(7))
          .getPayload();

      String userId = claims.getSubject();

      UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(userId, null,
          List.<GrantedAuthority>of());
      SecurityContextHolder.getContext().setAuthentication(auth);
    } catch (JwtException | IllegalArgumentException e) {
      response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
      response.getWriter().write("{\"error\": \"Invalid or expired token\"}");
      return;
    }

    chain.doFilter(request, response);
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();
    return path.startsWith("/api/v1/webhook") || path.startsWith("/api/webhook") || path.startsWith("/actuator") || path.equals("/error");
  }

  @Override
  protected boolean shouldNotFilterAsyncDispatch() {
    return false;
  }

  @Override
  protected boolean shouldNotFilterErrorDispatch() {
    return false;
  }
}
