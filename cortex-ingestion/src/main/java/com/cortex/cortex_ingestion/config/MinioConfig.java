package com.cortex.cortex_ingestion.config;

import java.util.LinkedList;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;

import io.minio.BucketExistsArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.SetBucketNotificationArgs;
import io.minio.messages.EventType;
import io.minio.messages.NotificationConfiguration;
import io.minio.messages.QueueConfiguration;

@Configuration
public class MinioConfig {

  @Value("${minio.url}")
  private String url;

  @Value("${minio.external-url}")
  private String externalUrl;

  @Value("${minio.access-key}")
  private String accessKey;

  @Value("${minio.secret-key}")
  private String secretKey;

  @Value("${minio.bucket}")
  private String bucket;

  @Value("${minio.region}")
  private String region;

  @Bean
  public MinioClient minioClient() {
    return MinioClient.builder().endpoint(url).credentials(accessKey, secretKey).region(region).build();
  }

  @Bean(name = "externalMinioClient")
  public MinioClient externalMinioClient() {
    return MinioClient.builder()
        .endpoint(externalUrl)
        .credentials(accessKey, secretKey)
        .region(region)
        .build();
  }

  @Profile("dev")
  @Bean
  public CommandLineRunner initBucket(MinioClient minioClient) {
    return args -> {
      try {
        boolean bucketfound = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucket).build());

        if (!bucketfound) {
          minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
          System.out.println("Bucket " + bucket + " created");
        } else {
          System.out.println("Bucket " + bucket + " already exists");
        }

        System.out.println("Configuring bucket notifications....");

        NotificationConfiguration notificationConfiguration = new NotificationConfiguration();
        List<QueueConfiguration> queueConfigurations = new LinkedList<>();

        QueueConfiguration queueConfiguration = new QueueConfiguration();
        queueConfiguration.setQueue("arn:minio:sqs::primary:webhook");
        queueConfiguration.setEvents(List.of(EventType.OBJECT_CREATED_PUT));

        queueConfigurations.add(queueConfiguration);
        notificationConfiguration.setQueueConfigurationList(queueConfigurations);

        minioClient.setBucketNotification(
            SetBucketNotificationArgs.builder().bucket(bucket).config(notificationConfiguration).build());

        System.out.println("Bucket notifications enabled: pointing to primary webhook.");
      } catch (Exception e) {
        System.err.println("Error initializing bucket: " + e.getMessage());
      }
    };
  }
}