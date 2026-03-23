package com.cortex.cortex_rag_orchestration.controller;

import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.cortex.cortex_common.dto.SearchRequestDTO;
import com.cortex.cortex_common.dto.SearchResultDTO;
import com.cortex.cortex_rag_orchestration.service.SearchService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

@Slf4j
@RequiredArgsConstructor
@RestController
@RequestMapping("/api/v1/search")
public class SearchController {

  private final SearchService searchService;

  @PostMapping
  public ResponseEntity<List<SearchResultDTO>> search(@RequestBody SearchRequestDTO request) {

    List<SearchResultDTO> results = searchService.search(request);

    return ResponseEntity.ok(results);
  }

}
