package com.cortex.cortex_common.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.cortex.cortex_common.model.MediaChunk;

@Repository
public interface MediaChunkRepository extends JpaRepository<MediaChunk, UUID> {

}
