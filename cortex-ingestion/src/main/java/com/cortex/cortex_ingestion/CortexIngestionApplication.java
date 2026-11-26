package com.cortex.cortex_ingestion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.scheduling.annotation.EnableScheduling;

@EnableScheduling // the sweep (service/Sweep)
@SpringBootApplication(scanBasePackages = { "com.cortex.cortex_ingestion", "com.cortex.cortex_common" })
@EntityScan(basePackages = { "com.cortex.cortex_ingestion.model", "com.cortex.cortex_common.model" })
@EnableJpaRepositories(basePackages = { "com.cortex.cortex_ingestion.repository", "com.cortex.cortex_common.repository" })
public class CortexIngestionApplication {

	public static void main(String[] args) {
		SpringApplication.run(CortexIngestionApplication.class, args);
	}

}
