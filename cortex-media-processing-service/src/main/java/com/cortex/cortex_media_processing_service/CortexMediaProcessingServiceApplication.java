package com.cortex.cortex_media_processing_service;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = { "com.cortex.cortex_media_processing_service.model", "com.cortex.cortex_common.model" })
@EnableJpaRepositories(basePackages = { "com.cortex.cortex_media_processing_service.repository" })
public class CortexMediaProcessingServiceApplication {

	public static void main(String[] args) {
		SpringApplication.run(CortexMediaProcessingServiceApplication.class, args);
	}

}
