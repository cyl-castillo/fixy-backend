package com.fixy.backend.service;

import java.util.Locale;
import java.util.Set;
import org.springframework.http.HttpStatus;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Validación de imágenes compartida entre {@link LeadPhotoService} (fotos
 * de lead) y {@link ProviderPhotoService} (foto de perfil del proveedor,
 * Tier 2 contrato §B.1) — mismo formato permitido (jpg/png/webp), mismo
 * criterio de extensión desde el content-type, límite de tamaño parametrizable
 * porque cada superficie tiene el suyo (6 MB fotos de lead, 5 MB avatar).
 */
final class ImageUploadValidator {

  static final Set<String> ALLOWED_CONTENT_TYPES = Set.of(
      "image/jpeg", "image/jpg", "image/png", "image/webp"
  );

  private ImageUploadValidator() {
  }

  static void validate(MultipartFile file, long maxBytes) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "archivo vacio");
    }
    if (file.getSize() > maxBytes) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "archivo demasiado grande (max %d MB)".formatted(maxBytes / (1024 * 1024)));
    }
    String contentType = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    if (!ALLOWED_CONTENT_TYPES.contains(contentType)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formato no permitido (usa jpg, png o webp)");
    }
  }

  static String extensionFor(String contentType, String originalFilename) {
    String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    return switch (ct) {
      case "image/jpeg", "image/jpg" -> ".jpg";
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      default -> {
        if (originalFilename == null) yield "";
        int dot = originalFilename.lastIndexOf('.');
        yield dot >= 0 ? originalFilename.substring(dot).toLowerCase(Locale.ROOT) : "";
      }
    };
  }
}
