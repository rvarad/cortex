package com.cortex.cortex_ingestion.controller;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.cortex.cortex_ingestion.dto.FileResponseDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLRequestDTO;
import com.cortex.cortex_ingestion.dto.GetPresignedURLResponseDTO;
import com.cortex.cortex_ingestion.dto.PlaybackUrlResponseDTO;
import com.cortex.cortex_ingestion.dto.UpdateFileRequestDTO;
import com.cortex.cortex_ingestion.service.FilesService;
import com.cortex.cortex_ingestion.service.GcsStorageService;
import com.cortex.cortex_ingestion.service.PipelineEventsService;

import jakarta.validation.Valid;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RestController
@RequestMapping("/api/v1/files")
public class FilesController {

  private final GcsStorageService gcsStorageService;

  private final FilesService filesService;

  private final PipelineEventsService pipelineEventsService;

  public FilesController(GcsStorageService gcsStorageService, FilesService filesService,
      PipelineEventsService pipelineEventsService) {
    this.gcsStorageService = gcsStorageService;
    this.filesService = filesService;
    this.pipelineEventsService = pipelineEventsService;
  }

  @PostMapping("/upload")
  public ResponseEntity<GetPresignedURLResponseDTO> generatePresignedUrl(
      @Valid @RequestBody GetPresignedURLRequestDTO requestBody, Authentication authentication) {
    log.info("Received request for presigned url for file: {}", requestBody);

    String userId = authentication.getName();
    GetPresignedURLResponseDTO uploadUrl = gcsStorageService.getPresignedURL(requestBody.getFilename(),
        requestBody.getContentType(), requestBody.getFileSize(), userId);

    return ResponseEntity.ok(uploadUrl);
  }

  @GetMapping
  public ResponseEntity<List<FileResponseDTO>> getAllFiles(Authentication authentication) {

    String userId = authentication.getName();
    List<FileResponseDTO> response = filesService.getAllFiles(userId);

    return ResponseEntity.ok(response);
  }

  @DeleteMapping("/{fileId}")
  public ResponseEntity<Void> deleteFile(@PathVariable UUID fileId, Authentication authentication) {
    log.info("Received request to delete file: {}", fileId);

    String userId = authentication.getName();
    filesService.deleteFile(fileId, userId);

    return ResponseEntity.noContent().build();
  }

  @PatchMapping("update/{fileId}")
  public ResponseEntity<Void> updateFile(
      @PathVariable UUID fileId,
      @Valid @RequestBody UpdateFileRequestDTO request, Authentication authentication) {

    log.info("Received request to update file display name for fileId: {} to '{}'", fileId, request.displayName());

    String userId = authentication.getName();
    filesService.updateFileDisplayName(fileId, request.displayName(), userId);
    return ResponseEntity.noContent().build();
  }

  @GetMapping(value = "/{fileId}/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
  public SseEmitter getPipelineEvents(@PathVariable UUID fileId, Authentication authentication) {
    log.info("Received request for pipeline events for fileId: {}", fileId);

    String userId = authentication.getName();
    return pipelineEventsService.subscribeToEvents(fileId, userId);
  }

  @GetMapping("/{fileId}/playback-url")
  public ResponseEntity<PlaybackUrlResponseDTO> getPlaybackUrl(@PathVariable UUID fileId,
      Authentication authentication) {

    String userId = authentication.getName();

    PlaybackUrlResponseDTO response = gcsStorageService.signPlaybackUrl(fileId, userId);

    return ResponseEntity.ok(response);
  }

}