package com.cortex.cortex_gateway.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.web.WebAttributes;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;

/**
 * The login's boundary. Every failed login goes back to the frontend, which says so; the failure
 * is logged once, here: ours at ERROR with the cause, the user's or Google's at WARN.
 */
class LoginFailureHandlerTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  private final LoginFailureHandler handler = new LoginFailureHandler("http://localhost:3000");

  @BeforeEach
  void setUp() {
    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
  }

  @Test
  void aFailedSync_goesBackToTheFrontend_andIsLoggedOnceAsAnError_withTheCause() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(new MockHttpServletRequest(), response, new OAuth2AuthenticationException(
        new OAuth2Error("user_sync_failed", "User sync failed for google-sub-1", null),
        Status.UNAVAILABLE.asRuntimeException()));

    assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/?login=failed");
    List<ILoggingEvent> lines = captured.list;
    assertThat(lines).as("log lines").hasSize(1);
    assertThat(lines.get(0).getLevel()).isEqualTo(Level.ERROR);
    assertThat(lines.get(0).getThrowableProxy()).as("with the exception attached").isNotNull();
  }

  @Test
  void aLoginRefusedAtGoogle_goesBackToTheFrontend_andIsLoggedOnceAsAWarning() throws Exception {
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(new MockHttpServletRequest(), response,
        new OAuth2AuthenticationException(new OAuth2Error("access_denied")));

    assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/?login=failed");
    List<ILoggingEvent> lines = captured.list;
    assertThat(lines).as("log lines").hasSize(1);
    assertThat(lines.get(0).getLevel()).isEqualTo(Level.WARN);
    assertThat(lines.get(0).getThrowableProxy()).as("no stack trace: not our failure").isNull();
  }

  @Test
  void aFailedLogin_storesNothingInTheSession() throws Exception {
    // The real request already has a session: it holds the OAuth2 authorization request. Sessions
    // live in Redis and are serialised before the response goes out, and a failed sync's cause
    // holds io.grpc.Status, which isn't serialisable: storing the exception there turned the
    // redirect into a 500. The frontend reads only the URL, so nothing needs storing.
    MockHttpServletRequest request = new MockHttpServletRequest();
    MockHttpSession session = new MockHttpSession();
    request.setSession(session);
    MockHttpServletResponse response = new MockHttpServletResponse();

    handler.onAuthenticationFailure(request, response, new OAuth2AuthenticationException(
        new OAuth2Error("user_sync_failed", "User sync failed for google-sub-1", null),
        Status.UNAVAILABLE.asRuntimeException()));

    assertThat(session.getAttribute(WebAttributes.AUTHENTICATION_EXCEPTION)).as("exception stored in the session")
        .isNull();
    assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:3000/?login=failed");
  }
}
