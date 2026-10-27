package com.cortex.cortex_gateway.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;

import com.cortex.cortex_common.grpc.SyncUserRequest;
import com.cortex.cortex_common.grpc.SyncUserResponse;
import com.cortex.cortex_common.grpc.UserServiceGrpc;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.Status;

/**
 * The user sync runs while Spring loads the user, before it saves the login to the session. A
 * failed sync must fail the login: Spring then saves no session and calls the failure handler.
 *
 * <p>The delegate is a fake: the real OidcUserService calls Google's userinfo endpoint.
 */
class SyncingOidcUserServiceTest {

  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  private final UserServiceGrpc.UserServiceBlockingStub userGrpcStub = mock(UserServiceGrpc.UserServiceBlockingStub.class);

  private final OidcUser googleUser = new DefaultOidcUser(List.of(), OidcIdToken.withTokenValue("id-token")
      .subject("google-sub-1")
      .claim("email", "user@example.com")
      .claim("name", "A User")
      .claim("picture", "https://example.com/a.png")
      .issuedAt(Instant.now())
      .expiresAt(Instant.now().plusSeconds(60))
      .build());

  private final SyncingOidcUserService service = new SyncingOidcUserService(request -> googleUser, userGrpcStub);

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
  void aSuccessfulSync_returnsTheUser_afterSendingTheirDetails() {
    when(userGrpcStub.syncUser(any())).thenReturn(SyncUserResponse.newBuilder().setSuccess(true).build());

    OidcUser loaded = service.loadUser(mock(OidcUserRequest.class));

    assertThat(loaded).isSameAs(googleUser);
    verify(userGrpcStub).syncUser(SyncUserRequest.newBuilder()
        .setId("google-sub-1")
        .setEmail("user@example.com")
        .setName("A User")
        .setPictureUrl("https://example.com/a.png")
        .build());
  }

  @Test
  void aFailedSync_failsTheLogin_withTheCauseAttached_andLogsNothing() {
    // Logging is the failure handler's job, the login's boundary.
    when(userGrpcStub.syncUser(any())).thenThrow(Status.UNAVAILABLE.asRuntimeException());

    OAuth2AuthenticationException failure = catchThrowableOfType(OAuth2AuthenticationException.class,
        () -> service.loadUser(mock(OidcUserRequest.class)));

    assertThat(failure).as("the login fails").isNotNull();
    assertThat(failure.getError().getErrorCode()).isEqualTo("user_sync_failed");
    assertThat(failure.getCause()).isInstanceOf(io.grpc.StatusRuntimeException.class);
    assertThat(captured.list).as("log lines").isEmpty();
  }
}
