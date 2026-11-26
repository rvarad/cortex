package com.cortex.cortex_ingestion.exception;

import org.springframework.grpc.server.exception.GrpcExceptionHandler;
import org.springframework.stereotype.Component;

import io.grpc.Status;
import io.grpc.StatusException;
import lombok.extern.slf4j.Slf4j;

/**
 * The boundary for every gRPC call ingestion serves (ADR-0014). Spring gRPC's
 * GrpcExceptionHandlerInterceptor calls it when a call throws; code below it lets failures
 * propagate. The caller gets INTERNAL; what went wrong stays in the log.
 */
@Slf4j
@Component
public class GrpcCallExceptionHandler implements GrpcExceptionHandler {

  @Override
  public StatusException handleException(Throwable exception) {
    log.error("gRPC call failed", exception);
    return Status.INTERNAL.withDescription("Internal error").asException();
  }
}
