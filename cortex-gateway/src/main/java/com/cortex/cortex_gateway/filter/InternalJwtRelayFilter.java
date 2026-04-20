package com.cortex.cortex_gateway.filter;

import java.io.IOException;
import java.util.Collections;
import java.util.Enumeration;
import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import com.cortex.cortex_gateway.service.InternalJwtService;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;

@Component
@RequiredArgsConstructor
public class InternalJwtRelayFilter extends OncePerRequestFilter {

  private final InternalJwtService jwtService;

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {

    Authentication auth = SecurityContextHolder.getContext().getAuthentication();

    if (auth != null && auth instanceof OAuth2AuthenticationToken oauth2Token) {
      String userId = oauth2Token.getPrincipal().getAttribute("sub");
      String email = oauth2Token.getPrincipal().getAttribute("email");

      String internalJwt = jwtService.mint(userId, email);

      HttpServletRequestWrapper wrappedRequest = new HttpServletRequestWrapper(request) {
        @Override
        public String getHeader(String name) {
          if ("Authorization".equalsIgnoreCase(name)) {
            return "Bearer " + internalJwt;
          }
          return super.getHeader(name);
        }

        @Override
        public Enumeration<String> getHeaders(String name) {
          if ("Authorization".equalsIgnoreCase(name)) {
            return Collections.enumeration(List.of("Bearer " + internalJwt));
          }
          return super.getHeaders(name);
        }

        @Override
        public Enumeration<String> getHeaderNames() {
          List<String> names = new java.util.ArrayList<>(Collections.list(super.getHeaderNames()));
          if (!names.contains("Authorization")) {
            names.add("Authorization");
          }
          return Collections.enumeration(names);
        }
      };

      chain.doFilter(wrappedRequest, response);
    } else {
      chain.doFilter(request, response);
    }
  }

  @Override
  protected boolean shouldNotFilter(HttpServletRequest request) {
    String path = request.getRequestURI();

    return path.startsWith("/api/webhook")
        || path.startsWith("/auth")
        || path.startsWith("/actuator")
        || path.startsWith("/oauth2")
        || path.startsWith("/login");
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
