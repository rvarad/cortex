package com.cortex.cortex_ingestion.logging;

import java.io.IOException;

import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Clears MDC when a request ends. Code below the HTTP boundary puts ids such as fileId into MDC and
 * leaves them there, so the boundary's one log line still carries them (ADR-0014). Tomcat reuses
 * its threads: without this, those ids would show up on the next request's lines.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class MdcCleanupFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
      throws ServletException, IOException {
    try {
      chain.doFilter(request, response);
    } finally {
      MDC.clear();
    }
  }
}
