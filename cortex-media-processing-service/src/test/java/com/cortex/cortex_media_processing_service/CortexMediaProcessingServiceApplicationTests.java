package com.cortex.cortex_media_processing_service;

import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@Disabled("Full-context-loaded test needs postgres+kafka+gcs+ai creds. Enable with TestContainers.")
@SpringBootTest
class CortexMediaProcessingServiceApplicationTests {

	@Test
	void contextLoads() {
	}

}
