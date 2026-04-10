package com.cortex.cortex_ingestion.service;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cortex.cortex_common.dto.PipelineEventDTO;

import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
public class SseEmitterRegistry {

  private final ConcurrentHashMap<UUID, List<SseEmitter>> emitterRegistry = new ConcurrentHashMap<>();

  public void addEmitter(UUID fileId, SseEmitter emitter) {
    emitterRegistry.computeIfAbsent(fileId, k -> new CopyOnWriteArrayList<>()).add(emitter);

    Runnable removeCallback = () -> removeConnection(fileId, emitter);
    emitter.onCompletion(removeCallback);
    emitter.onTimeout(removeCallback);
    emitter.onError(e -> removeCallback.run());
  }

  private void removeConnection(UUID fileId, SseEmitter emitter) {
    List<SseEmitter> list = emitterRegistry.get(fileId);

    if (list != null) {
      list.remove(emitter);

      if (list.isEmpty()) {
        emitterRegistry.remove(fileId);
      }
    }
  }

  public void broadcast(PipelineEventDTO event) {
    List<SseEmitter> activeEmitters = emitterRegistry.get(event.getFileId());

    if (activeEmitters == null || activeEmitters.isEmpty())
      return;

    activeEmitters.forEach(emitter -> {
      try {
        emitter.send(SseEmitter.event().name(event.getEventType().name()).data(event));
      } catch (Exception e) {
        log.error("Failed to send event to emitter for fileId {}.", event.getFileId(), e);
        removeConnection(event.getFileId(), emitter);
      }
    });
  }

  public void completeEmitters(UUID fileId) {
    List<SseEmitter> activeEmitters = emitterRegistry.remove(fileId);
    if (activeEmitters == null || activeEmitters.isEmpty())
      return;

    activeEmitters.forEach(emitter -> {
      try {
        emitter.complete();
      } catch (Exception e) {
        log.error("Failed to complete emitter for fileId {}.", fileId, e);
      }
    });

    log.info("[SseEmitterRegistry] Completed and removed all emitters for fileId: {}", fileId);
  }
}
