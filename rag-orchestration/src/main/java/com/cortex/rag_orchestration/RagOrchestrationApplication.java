package com.cortex.rag_orchestration;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.domain.EntityScan;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

@SpringBootApplication
@EntityScan(basePackages = { "com.cortex.cortex_common.model", "com.cortex.rag_orchestration" })
@EnableJpaRepositories(basePackages = "com.cortex.rag_orchestration.repository")
public class RagOrchestrationApplication {

	public static void main(String[] args) {
		SpringApplication.run(RagOrchestrationApplication.class, args);
	}

}
