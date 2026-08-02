package com.cortex.cortex_rag_orchestration.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_common.model.FileMetadata;
import com.cortex.cortex_common.model.FileStatusEnum;
import com.cortex.cortex_common.model.MediaChunk;
import com.cortex.cortex_common.repository.FileMetadataRepository;
import com.cortex.cortex_common.repository.MediaChunkRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.ai.embedding.EmbeddingModel;

/**
 * Pure unit test for the G.0 context-budget rule in SearchService.getFileContext.
 * No Spring, no database, no embeddings.
 *
 * The rule: a file whose chunks fit the token budget is STUFFED whole (so vague
 * questions like "summarise this video" see the entire file); a file that does
 * not fit falls back to hybrid retrieval.
 *
 * Budget here is 100 tokens => the cutover sits at 400 characters (chars / 4).
 */
class SearchServiceTest {

  private static final int TOKEN_BUDGET = 100;

  private final EmbeddingModel embeddingModel = mock(EmbeddingModel.class);
  private final MediaChunkRepository mediaChunkRepository = mock(MediaChunkRepository.class);
  private final FileMetadataRepository fileMetadataRepository = mock(FileMetadataRepository.class);

  private final SearchService searchService = new SearchService(
      embeddingModel, mediaChunkRepository, fileMetadataRepository, TOKEN_BUDGET);

  private final UUID fileId = UUID.randomUUID();

  @Test
  void underBudget_stuffsEveryChunkInChunkIndexOrder_withoutRetrieving() {
    // ---- Arrange ----
    // Three small chunks: well under 400 chars total, so the whole file fits.
    // The repository contract already orders by chunkIndex ASC; citation numbering
    // depends on that order surviving all the way to the DTO list.
    List<MediaChunk> chunks = List.of(
        chunk(0, 0.0, 30.0, "intro to docker", "title slide"),
        chunk(1, 30.0, 60.0, "docker images", "a terminal"),
        chunk(2, 60.0, 90.0, "docker compose", "a yaml file"));

    when(mediaChunkRepository.findByFileIdAndUserIdAndStatusOrderByChunkIndexAsc(
        fileId, "user-1", MediaChunk.Status.COMPLETED)).thenReturn(chunks);
    when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(fileMetadata("demo.mp4")));

    // ---- Act ----
    List<SearchResultDTO> results = searchService.getFileContext(request("summarise this video"), "user-1");

    // ---- Assert ----
    // Every chunk is present, in chunkIndex order — this is what makes a whole-file
    // summary possible; retrieval would have returned only the "relevant" few.
    assertThat(results).extracting(SearchResultDTO::getChunkIndex).containsExactly(0, 1, 2);
    assertThat(results).extracting(SearchResultDTO::getTranscript)
        .containsExactly("intro to docker", "docker images", "docker compose");
    // Timestamps are carried through, so citations can still deep-link into the video.
    assertThat(results).extracting(SearchResultDTO::getStartTime).containsExactly(0.0, 30.0, 60.0);
    // The file name is resolved once, not per chunk.
    assertThat(results).extracting(SearchResultDTO::getFileDisplayName).containsOnly("demo.mp4");
    // Nothing was ranked, so there is no meaningful score.
    assertThat(results).extracting(SearchResultDTO::getScore).containsOnly(0.0);

    // The core guarantee: stuffing does not embed and does not search.
    verifyNoInteractions(embeddingModel);
    verify(mediaChunkRepository, never()).semanticSearch(anyString(), any(), any(), anyInt());
    verify(mediaChunkRepository, never()).lexicalSearch(anyString(), anyString(), anyString(), any(), anyInt());
  }

  @Test
  void overBudget_fallsBackToHybridRetrieval() {
    // ---- Arrange ----
    // One chunk of 1000 chars => ~250 tokens => over the 100-token budget.
    MediaChunk huge = chunk(0, 0.0, 30.0, "x".repeat(1000), null);

    when(mediaChunkRepository.findByFileIdAndUserIdAndStatusOrderByChunkIndexAsc(
        fileId, "user-1", MediaChunk.Status.COMPLETED)).thenReturn(List.of(huge));
    when(embeddingModel.embed(anyString())).thenReturn(new float[768]);
    when(mediaChunkRepository.semanticSearch(anyString(), any(), any(), anyInt())).thenReturn(List.of());
    when(mediaChunkRepository.lexicalSearch(anyString(), anyString(), anyString(), any(), anyInt()))
        .thenReturn(List.of());

    // ---- Act ----
    searchService.getFileContext(request("what did he say about docker?"), "user-1");

    // ---- Assert ----
    // Embedding the query is the fingerprint of the retrieval path — an oversized
    // file must not be stuffed into the prompt.
    verify(embeddingModel).embed("what did he say about docker?");
    verify(mediaChunkRepository).semanticSearch(eq("user-1"), any(), eq(fileId), anyInt());
    verify(mediaChunkRepository).lexicalSearch(eq("user-1"), anyString(), anyString(), eq(fileId), anyInt());
  }

  @Test
  void nullTranscriptOrVisualSummary_doesNotBlowUpTheBudgetCount() {
    // ---- Arrange ----
    // A chunk whose vision step produced nothing (visualSummary null) and one whose
    // audio step produced nothing (transcript null). Both are real pipeline outcomes.
    when(mediaChunkRepository.findByFileIdAndUserIdAndStatusOrderByChunkIndexAsc(
        fileId, "user-1", MediaChunk.Status.COMPLETED))
        .thenReturn(List.of(
            chunk(0, 0.0, 30.0, "audio only", null),
            chunk(1, 30.0, 60.0, null, "vision only")));
    when(fileMetadataRepository.findById(fileId)).thenReturn(Optional.of(fileMetadata("demo.mp4")));

    // ---- Act / Assert ----
    assertThatCode(() -> searchService.getFileContext(request("summarise this video"), "user-1"))
        .doesNotThrowAnyException();

    assertThat(searchService.getFileContext(request("summarise this video"), "user-1"))
        .extracting(SearchResultDTO::getChunkIndex).containsExactly(0, 1);
  }

  @Test
  void fileWithNoCompletedChunks_returnsEmpty_soChatCanSayItDoesNotKnow() {
    when(mediaChunkRepository.findByFileIdAndUserIdAndStatusOrderByChunkIndexAsc(
        fileId, "user-1", MediaChunk.Status.COMPLETED)).thenReturn(List.of());

    assertThat(searchService.getFileContext(request("summarise this video"), "user-1")).isEmpty();

    verifyNoInteractions(embeddingModel, fileMetadataRepository);
  }

  @Test
  void nullFileId_isLibraryWideAndAlwaysRetrieves() {
    // ---- Arrange ----
    // No fileId => the question spans the whole library => there is no single file
    // to stuff. (This is the branch the agent's search_library tool will own later.)
    SearchRequestDTO libraryWide = SearchRequestDTO.builder().query("which video mentions docker?").build();
    when(embeddingModel.embed(anyString())).thenReturn(new float[768]);

    // ---- Act ----
    searchService.getFileContext(libraryWide, "user-1");

    // ---- Assert ----
    verify(embeddingModel).embed("which video mentions docker?");
    verify(mediaChunkRepository, never())
        .findByFileIdAndUserIdAndStatusOrderByChunkIndexAsc(any(), anyString(), any());
  }

  private SearchRequestDTO request(String query) {
    return SearchRequestDTO.builder().query(query).fileId(fileId).build();
  }

  private MediaChunk chunk(int index, double start, double end, String transcript, String visualSummary) {
    return MediaChunk.builder()
        .id(UUID.randomUUID())
        .fileId(fileId)
        .chunkIndex(index)
        .startTime(start)
        .endTime(end)
        .status(MediaChunk.Status.COMPLETED)
        .languageCode("en")
        .transcript(transcript)
        .visualSummary(visualSummary)
        .userId("user-1")
        .build();
  }

  private FileMetadata fileMetadata(String displayName) {
    return FileMetadata.builder()
        .id(fileId)
        .fileDisplayName(displayName)
        .bucketName("cortex-media")
        .objectName("user-1/" + displayName)
        .fileSize(1024L)
        .fileStatus(FileStatusEnum.COMPLETED)
        .contentType("video/mp4")
        .userId("user-1")
        .build();
  }
}
