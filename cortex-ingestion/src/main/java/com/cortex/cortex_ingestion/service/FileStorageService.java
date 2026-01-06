package com.cortex.cortex_ingestion.service;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.multipart.MultipartFile;

// @Service
public class FileStorageService {

  private final Path fileStorageLocation;

  public FileStorageService(@Value("${cortex.upload-dir}") String uploadDir) {
    this.fileStorageLocation = Paths.get(uploadDir).toAbsolutePath().normalize();

    try {
      Files.createDirectories(this.fileStorageLocation);
    } catch (Exception e) {
      throw new RuntimeException("Could not create the upload directory", e);
    }
  }

  public String storeFile(MultipartFile file) {
    String originalFileName = file.getOriginalFilename();

    if (originalFileName == null)
      originalFileName = "unknown_file";

    String fileExtension = "";
    int i = originalFileName.lastIndexOf('.');
    if (i > 0) {
      fileExtension = originalFileName.substring(i);
    }

    String newFileName = UUID.randomUUID().toString() + fileExtension;

    try {
      Path targetLocation = this.fileStorageLocation.resolve(newFileName);

      Files.copy(file.getInputStream(), targetLocation, StandardCopyOption.REPLACE_EXISTING);

      return newFileName;
    } catch (IOException e) {
      throw new RuntimeException("Could not store file " + newFileName + ". Please try again!", e);
    } catch (Exception e) {
      throw new RuntimeException("Error storing file", e);
    }
  }

}