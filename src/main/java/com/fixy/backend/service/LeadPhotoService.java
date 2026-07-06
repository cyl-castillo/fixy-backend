package com.fixy.backend.service;

import com.fixy.backend.dto.LeadPhotoResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadPhoto;
import com.fixy.backend.repository.LeadPhotoRepository;
import com.fixy.backend.repository.LeadRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

@Service
public class LeadPhotoService {

  private static final long MAX_BYTES = 6 * 1024 * 1024; // 6 MB
  private static final int MAX_PER_LEAD = 12;
  private static final Set<String> ALLOWED_CT = Set.of(
      "image/jpeg", "image/jpg", "image/png", "image/webp"
  );

  private final LeadPhotoRepository photoRepository;
  private final LeadRepository leadRepository;
  private final LeadTimelineService timelineService;
  private final Path uploadsRoot;
  private final String urlPrefix;
  private final SecureRandom random = new SecureRandom();

  public LeadPhotoService(
      LeadPhotoRepository photoRepository,
      LeadRepository leadRepository,
      LeadTimelineService timelineService,
      @Value("${fixy.uploads.dir:./data/uploads}") String uploadsDir,
      @Value("${fixy.uploads.url-prefix:/uploads}") String urlPrefix
  ) {
    this.photoRepository = photoRepository;
    this.leadRepository = leadRepository;
    this.timelineService = timelineService;
    this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
    this.urlPrefix = urlPrefix.replaceAll("/+$", "");
    try {
      Files.createDirectories(this.uploadsRoot);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create uploads dir: " + this.uploadsRoot, e);
    }
  }

  public List<LeadPhotoResponse> listForLead(Long leadId) {
    return photoRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .map(p -> LeadPhotoResponse.fromEntity(p, urlPrefix))
        .toList();
  }

  public LeadPhotoResponse uploadAsProvider(Long leadId, Long providerId, MultipartFile file, String caption) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    return store(lead, providerId, file, caption, "PROVIDER_PHOTO_UPLOADED", "provider");
  }

  /** Cliente sube una foto autenticado con el accessToken del lead (mismo patrón que los mensajes). */
  public LeadPhotoResponse uploadAsCustomer(Long leadId, String token, MultipartFile file, String caption) {
    Lead lead = requireLeadAndToken(leadId, token);
    return store(lead, null, file, caption, "CUSTOMER_PHOTO_UPLOADED", "customer");
  }

  private Lead requireLeadAndToken(Long leadId, String token) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    return lead;
  }

  private LeadPhotoResponse store(
      Lead lead, Long providerId, MultipartFile file, String caption, String eventType, String eventActor
  ) {
    Long leadId = lead.getId();
    validate(file);
    if (photoRepository.countByLeadId(leadId) >= MAX_PER_LEAD) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "se alcanzo el maximo de fotos para este pedido");
    }

    String extension = extensionFor(file.getContentType(), file.getOriginalFilename());
    String hex = randomHex(8);
    String relative = "lead-" + leadId + "/" + hex + extension;
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

    LeadPhoto photo = new LeadPhoto();
    photo.setLeadId(leadId);
    photo.setProviderId(providerId);
    photo.setRelativePath(relative);
    photo.setOriginalName(file.getOriginalFilename());
    photo.setContentType(file.getContentType());
    photo.setSizeBytes(file.getSize());
    photo.setCaption(caption != null ? caption.trim() : null);
    LeadPhoto saved = photoRepository.save(photo);

    timelineService.appendEvent(lead, eventType, eventActor,
        "Foto agregada (%d bytes)".formatted(file.getSize()));

    return LeadPhotoResponse.fromEntity(saved, urlPrefix);
  }

  private void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "archivo vacio");
    }
    if (file.getSize() > MAX_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "archivo demasiado grande (max 6 MB)");
    }
    String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    if (!ALLOWED_CT.contains(ct)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formato no permitido (usa jpg, png o webp)");
    }
  }

  private String extensionFor(String contentType, String original) {
    String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT);
    return switch (ct) {
      case "image/jpeg", "image/jpg" -> ".jpg";
      case "image/png" -> ".png";
      case "image/webp" -> ".webp";
      default -> {
        if (original == null) yield "";
        int dot = original.lastIndexOf('.');
        yield dot >= 0 ? original.substring(dot).toLowerCase(Locale.ROOT) : "";
      }
    };
  }

  private String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    random.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }
}
