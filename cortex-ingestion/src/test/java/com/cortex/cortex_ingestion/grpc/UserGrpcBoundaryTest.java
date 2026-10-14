package com.cortex.cortex_ingestion.grpc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.grpc.autoconfigure.server.exception.GrpcExceptionHandlerAutoConfiguration;
import org.springframework.grpc.server.exception.GrpcExceptionHandlerInterceptor;

import com.cortex.cortex_common.grpc.SyncUserRequest;
import com.cortex.cortex_common.grpc.UserServiceGrpc;
import com.cortex.cortex_common.repository.UserRepository;
import com.cortex.cortex_ingestion.exception.GrpcCallExceptionHandler;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import io.grpc.ManagedChannel;
import io.grpc.Server;
import io.grpc.ServerInterceptors;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;

/**
 * A served gRPC call's boundary is GrpcCallExceptionHandler (ADR-0014), called by Spring gRPC's
 * GrpcExceptionHandlerInterceptor when the call throws. The caller gets INTERNAL with no internal
 * text, and the failure is logged once, there.
 *
 * <p>An in-process server runs the real UserGrpcService behind the same interceptor that Spring's
 * autoconfiguration installs. Only the repository is a fake.
 */
class UserGrpcBoundaryTest {

  /** Every logger of ours sits under "com.cortex", so this sees all of our lines. */
  private final Logger cortexLogger = (Logger) LoggerFactory.getLogger("com.cortex");

  private final ListAppender<ILoggingEvent> captured = new ListAppender<>();

  private final UserRepository userRepository = mock(UserRepository.class);

  private Server server;

  private ManagedChannel channel;

  @BeforeEach
  void setUp() throws Exception {
    String name = InProcessServerBuilder.generateName();
    server = InProcessServerBuilder.forName(name).directExecutor()
        .addService(ServerInterceptors.intercept(new UserGrpcService(userRepository),
            new GrpcExceptionHandlerInterceptor(new GrpcCallExceptionHandler())))
        .build()
        .start();
    channel = InProcessChannelBuilder.forName(name).directExecutor().build();
    captured.start();
    cortexLogger.addAppender(captured);
  }

  @AfterEach
  void tearDown() {
    cortexLogger.detachAppender(captured);
    channel.shutdownNow();
    server.shutdownNow();
  }

  @Test
  void aFailingSync_isInternal_withNoInternalText_andOneErrorLineFromTheBoundary() {
    when(userRepository.findById("user-1")).thenThrow(new IllegalStateException("db password is hunter2"));

    StatusRuntimeException failure = catchThrowableOfType(StatusRuntimeException.class,
        () -> UserServiceGrpc.newBlockingStub(channel).syncUser(SyncUserRequest.newBuilder()
            .setId("user-1").setEmail("user@example.com").setName("User").setPictureUrl("").build()));

    assertThat(failure).as("the call fails").isNotNull();
    assertThat(failure.getStatus().getCode()).isEqualTo(Status.Code.INTERNAL);
    assertThat(String.valueOf(failure.getStatus().getDescription())).doesNotContain("hunter2");

    List<ILoggingEvent> errors = captured.list.stream().filter(logged -> logged.getLevel() == Level.ERROR).toList();
    assertThat(errors).as("ERROR lines").hasSize(1);
    ILoggingEvent error = errors.get(0);
    assertThat(error.getLoggerName()).as("logged by the boundary")
        .isEqualTo(GrpcCallExceptionHandler.class.getName());
    assertThat(error.getThrowableProxy()).as("with the exception attached").isNotNull();
    assertThat(error.getThrowableProxy().getMessage()).isEqualTo("db password is hunter2");
  }

  @Test
  void springInstallsTheBoundary_fromComponentScanning() {
    new ApplicationContextRunner()
        .withConfiguration(AutoConfigurations.of(GrpcExceptionHandlerAutoConfiguration.class))
        .withUserConfiguration(ScanTheExceptionPackage.class)
        .run(context -> assertThat(context).hasSingleBean(GrpcExceptionHandlerInterceptor.class));
  }

  @Configuration
  @ComponentScan(basePackageClasses = GrpcCallExceptionHandler.class)
  static class ScanTheExceptionPackage {
  }
}
