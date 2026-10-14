package com.cortex.cortex_gateway.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

/**
 * The login's boundary (ADR-0014). Every failed login goes back to the frontend, which tells the
 * user; it is logged once, here. A failed user sync is ours: ERROR with the stack trace. Anything
 * else (the user cancelled at Google, an expired code) is not: WARN with the reason.
 */
@Slf4j
@Component
public class LoginFailureHandler extends SimpleUrlAuthenticationFailureHandler {

  public LoginFailureHandler(@Value("${app.cors.allowed-origins}") String allowedOrigins) {
    super(allowedOrigins + "/?login=failed");
  }

  @Override
  public void onAuthenticationFailure(HttpServletRequest request, HttpServletResponse response,
      AuthenticationException exception) throws IOException, ServletException {
    if (exception instanceof OAuth2AuthenticationException oauth2Exception
        && "user_sync_failed".equals(oauth2Exception.getError().getErrorCode())) {
      log.error("Login failed: {}", oauth2Exception.getError().getDescription(), exception);
    } else {
      log.warn("Login failed: {}", exception.getMessage());
    }
    super.onAuthenticationFailure(request, response, exception);
  }
}
