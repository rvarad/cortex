package com.cortex.cortex_gateway.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.client.GrpcChannelFactory;

import com.cortex.cortex_common.grpc.UserServiceGrpc;

@Configuration
public class GrpcClientConfig {

  @Bean
  public UserServiceGrpc.UserServiceBlockingStub userServiceStub(GrpcChannelFactory channelFactory) {
    return UserServiceGrpc.newBlockingStub(channelFactory.createChannel("ingestion-channel"));
  }
}
