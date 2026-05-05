package com.cortex.cortex_gateway.security;

import java.io.IOException;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.grpc.client.GrpcChannelFactory;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.authentication.SimpleUrlAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;

import com.cortex.cortex_common.grpc.SyncUserRequest;
import com.cortex.cortex_common.grpc.UserServiceGrpc;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class OAuth2LoginSuccessHandler extends SimpleUrlAuthenticationSuccessHandler {

  private final UserServiceGrpc.UserServiceBlockingStub userGrpcStub;

  public OAuth2LoginSuccessHandler(@Value("${app.cors.allowed-origins}") String allowedOrigins,
      GrpcChannelFactory channelFactory) {
    super.setDefaultTargetUrl(allowedOrigins + "/dashboard");
    super.setAlwaysUseDefaultTargetUrl(true);

    this.userGrpcStub = UserServiceGrpc.newBlockingStub(channelFactory.createChannel("ingestion-channel"));
  }

  @Override
  public void onAuthenticationSuccess(HttpServletRequest request, HttpServletResponse response,
      Authentication authentication) throws IOException, ServletException {

    if (authentication instanceof OAuth2AuthenticationToken oauth2Token) {
      OAuth2User principal = oauth2Token.getPrincipal();

      String id = principal.getAttribute("sub");
      String email = principal.getAttribute("email");
      String name = principal.getAttribute("name");
      String picture = principal.getAttribute("picture");

      try {
        SyncUserRequest syncRequest = SyncUserRequest.newBuilder().setId(id).setEmail(email != null ? email : "")
            .setName(name != null ? name : "")
            .setPictureUrl(picture != null ? picture : "").build();

        var grpcResponse = userGrpcStub.syncUser(syncRequest);
        log.info("gRPC Sync Response: {}", grpcResponse);
      } catch (Exception e) {
        log.error("Failed to sync user to ingestion service via gRPC", e);
      }

      super.onAuthenticationSuccess(request, response, authentication);
    }
  }

}
