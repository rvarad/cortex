package com.cortex.cortex_media_processing_service.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;

@Configuration
public class MinioConfig {

  @Value("${minio.url}")
  private String url;

  @Value("${minio.access-key}")
  private String accessKey;

  @Value("${minio.secret-key}")
  private String secretKey;

  @Value("${minio.bucket.download}")
  private String downloadBucket;

  @Value("${minio.bucket.chunks}")
  private String chunksBucket;

  @Bean
  public MinioClient minioClient() {
    return MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).build();
  }

  @Bean
  public CommandLineRunner initBucket(MinioClient minioClient) {
    return args -> {

      try {
        boolean bucketfound = minioClient.bucketExists(BucketExistsArgs.builder().bucket(chunksBucket).build());

        if (!bucketfound) {
          minioClient.makeBucket(MakeBucketArgs.builder().bucket(chunksBucket).build());
          System.out.println("Bucket " + chunksBucket + " created");
        } else {
          System.out.println("Bucket " + chunksBucket + " already exists");
        }

      } catch (Exception e) {
        System.err.println("Error initializing bucket: " + e.getMessage());
      }
    };
  }
}