package com.fixy.backend.service;

import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.ProviderRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Foto de perfil del proveedor (Tier 2, contrato §B.1) — mismo storage y
 * validación que {@link LeadPhotoService} (ver {@link ImageUploadValidator},
 * extraído para no duplicar), path propio {@code providers/{id}/avatar-*}.
 */
@Service
public class ProviderPhotoService {

  private static final long MAX_BYTES = 5 * 1024 * 1024; // 5 MB (contrato §B.1)

  private final ProviderRepository providerRepository;
  private final Path uploadsRoot;
  private final String urlPrefix;
  private final SecureRandom random = new SecureRandom();

  public ProviderPhotoService(
      ProviderRepository providerRepository,
      @Value("${fixy.uploads.dir:./data/uploads}") String uploadsDir,
      @Value("${fixy.uploads.url-prefix:/uploads}") String urlPrefix
  ) {
    this.providerRepository = providerRepository;
    this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
    this.urlPrefix = urlPrefix.replaceAll("/+$", "");
    try {
      Files.createDirectories(this.uploadsRoot);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create uploads dir: " + this.uploadsRoot, e);
    }
  }

  public Provider upload(Provider provider, MultipartFile file) {
    ImageUploadValidator.validate(file, MAX_BYTES);

    String extension = ImageUploadValidator.extensionFor(file.getContentType(), file.getOriginalFilename());
    String relative = "providers/%d/avatar-%d-%s%s".formatted(
        provider.getId(), System.currentTimeMillis(), randomHex(6), extension);
    Path target = uploadsRoot.resolve(relative).normalize();
    if (!target.startsWith(uploadsRoot)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid filename");
    }

    try {
      Files.createDirectories(target.getParent());
      try (var in = file.getInputStream()) {
        Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
      }
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "no se pudo guardar la foto");
    }

    deletePreviousFile(provider.getPhotoUrl());
    provider.setPhotoUrl(urlPrefix + "/" + relative);
    return providerRepository.save(provider);
  }

  public Provider remove(Provider provider) {
    deletePreviousFile(provider.getPhotoUrl());
    provider.setPhotoUrl(null);
    return providerRepository.save(provider);
  }

  private void deletePreviousFile(String previousUrl) {
    if (previousUrl == null || previousUrl.isBlank()) {
      return;
    }
    String prefix = urlPrefix + "/";
    if (!previousUrl.startsWith(prefix)) {
      return;
    }
    String relative = previousUrl.substring(prefix.length());
    try {
      Path target = uploadsRoot.resolve(relative).normalize();
      if (target.startsWith(uploadsRoot)) {
        Files.deleteIfExists(target);
      }
    } catch (IOException ex) {
      // best-effort: un archivo huérfano en disco no debe romper el upload nuevo
    }
  }

  private String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    random.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }
}
