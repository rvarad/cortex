package com.cortex.cortex_gateway.security;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserRequest;
import org.springframework.security.oauth2.client.oidc.userinfo.OidcUserService;
import org.springframework.security.oauth2.client.userinfo.OAuth2UserService;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.stereotype.Component;

import com.cortex.cortex_common.grpc.SyncUserRequest;
import com.cortex.cortex_common.grpc.UserServiceGrpc;

import io.grpc.StatusRuntimeException;
import lombok.extern.slf4j.Slf4j;

/**
 * Loads the Google user, then has ingestion create or find their users row. It runs before Spring
 * saves the login to the session, so a failed sync fails the login: no session, and the failure
 * handler sends the user back. (A success handler runs after the session is saved — too late.)
 */
@Slf4j
@Component
public class SyncingOidcUserService implements OAuth2UserService<OidcUserRequest, OidcUser> {

  private final OAuth2UserService<OidcUserRequest, OidcUser> delegate;

  private final UserServiceGrpc.UserServiceBlockingStub userGrpcStub;

  @Autowired
  public SyncingOidcUserService(UserServiceGrpc.UserServiceBlockingStub userGrpcStub) {
    this(new OidcUserService(), userGrpcStub);
  }

  SyncingOidcUserService(OAuth2UserService<OidcUserRequest, OidcUser> delegate,
      UserServiceGrpc.UserServiceBlockingStub userGrpcStub) {
    this.delegate = delegate;
    this.userGrpcStub = userGrpcStub;
  }

  @Override
  public OidcUser loadUser(OidcUserRequest userRequest) {
    OidcUser user = delegate.loadUser(userRequest);

    String id = user.getAttribute("sub");
    String email = user.getAttribute("email");
    String name = user.getAttribute("name");
    String picture = user.getAttribute("picture");

    SyncUserRequest syncRequest = SyncUserRequest.newBuilder().setId(id).setEmail(email != null ? email : "")
        .setName(name != null ? name : "")
        .setPictureUrl(picture != null ? picture : "").build();

    try {
      var grpcResponse = userGrpcStub.syncUser(syncRequest);
      log.info("gRPC Sync Response: {}", grpcResponse);
    } catch (StatusRuntimeException e) {
      // Not logged here: this only tells Spring the login failed. LoginFailureHandler logs it.
      throw new OAuth2AuthenticationException(new OAuth2Error("user_sync_failed", "User sync failed for " + id, null),
          e);
    }
    return user;
  }
}
