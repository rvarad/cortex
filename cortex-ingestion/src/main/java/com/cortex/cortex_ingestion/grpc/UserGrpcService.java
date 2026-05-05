package com.cortex.cortex_ingestion.grpc;

import org.springframework.stereotype.Service;

import com.cortex.cortex_common.grpc.SyncUserRequest;
import com.cortex.cortex_common.grpc.SyncUserResponse;
import com.cortex.cortex_common.grpc.UserServiceGrpc.UserServiceImplBase;
import com.cortex.cortex_common.model.User;
import com.cortex.cortex_common.repository.UserRepository;

import io.grpc.stub.StreamObserver;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
@RequiredArgsConstructor
public class UserGrpcService extends UserServiceImplBase {

  private final UserRepository userRepository;

  @Override
  @Transactional
  public void syncUser(SyncUserRequest request, StreamObserver<SyncUserResponse> responseObserver) {
    log.info("[UserGrpcService] Received gRPC request to sync user: {}", request.getEmail());

    try {
      User user = userRepository.findById(request.getId()).orElseGet(() -> User.builder()
          .id(request.getId())
          .email(request.getEmail())
          .name(request.getName())
          .pictureUrl(request.getPictureUrl())
          .build());

      userRepository.save(user);

      SyncUserResponse response = SyncUserResponse.newBuilder().setSuccess(true).setMessage("User synced successfully")
          .build();

      responseObserver.onNext(response);
      responseObserver.onCompleted();
    } catch (Exception e) {
      log.error("[UserGrpcService] Error syncing user: {}", e);
      responseObserver.onError(io.grpc.Status.INTERNAL
          .withDescription("Error during user sync")
          .withCause(e)
          .asRuntimeException());
    }
  }
}