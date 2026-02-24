package com.cortex.cortex_ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = { "com.cortex.cortex_ingestion.model", "com.cortex.cortex_common.model" })
@EnableJpaRepositories(basePackages = { "com.cortex.cortex_ingestion.repository" })
public class CortexIngestionApplication {

	public static void main(String[] args) {
		SpringApplication.run(CortexIngestionApplication.class, args);
	}

}
