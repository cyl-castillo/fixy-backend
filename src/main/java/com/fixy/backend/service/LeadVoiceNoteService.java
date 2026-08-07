package com.fixy.backend.service;

import com.fixy.backend.dto.LeadMessageResponse;
import com.fixy.backend.model.Lead;
import com.fixy.backend.repository.LeadRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.server.ResponseStatusException;

/**
 * Notas de voz del cliente (feature de accesibilidad 2026-08: las personas
 * mayores dictan, no tipean). El audio se guarda en el mismo uploads de las
 * fotos, se transcribe con OpenAI (misma key del agente) y la transcripción
 * entra al chat como un mensaje de cliente MÁS su audio — el proveedor lee
 * el texto y puede escuchar la voz original si la transcripción erró.
 *
 * Determinista por diseño ("lo crítico va en código, no en prompt"): si la
 * transcripción falla, la nota NO se pierde — entra con un texto fallback y
 * Fixy responde con un pedido claro de reintento; el turno del agente solo
 * corre cuando hay texto real que entender.
 */
@Service
public class LeadVoiceNoteService {

  /** ~2 minutos de opus/aac con margen; el frontend corta la grabación a los 120s. */
  private static final long MAX_BYTES = 10 * 1024 * 1024;
  public static final String FALLBACK_TEXT = "🎙️ Nota de voz";
  public static final String RETRY_REPLY =
      "Te mandé escuchar el audio pero no lo pude entender bien 🙏 ¿Me lo escribís en un mensaje, o probás grabarlo de nuevo?";

  private static final Set<String> ALLOWED_CT_PREFIXES = Set.of(
      "audio/webm", "audio/ogg", "audio/mp4", "audio/mpeg", "audio/aac",
      "audio/m4a", "audio/x-m4a", "audio/wav", "audio/3gpp", "video/webm", "video/mp4"
  );

  private final LeadRepository leadRepository;
  private final LeadMessageService messageService;
  private final TranscriptionService transcriptionService;
  private final CustomerMessageRouter messageRouter;
  private final Path uploadsRoot;
  private final String urlPrefix;
  private final SecureRandom random = new SecureRandom();

  public LeadVoiceNoteService(
      LeadRepository leadRepository,
      LeadMessageService messageService,
      TranscriptionService transcriptionService,
      CustomerMessageRouter messageRouter,
      @Value("${fixy.uploads.dir:./data/uploads}") String uploadsDir,
      @Value("${fixy.uploads.url-prefix:/uploads}") String urlPrefix
  ) {
    this.leadRepository = leadRepository;
    this.messageService = messageService;
    this.transcriptionService = transcriptionService;
    this.messageRouter = messageRouter;
    this.uploadsRoot = Path.of(uploadsDir).toAbsolutePath().normalize();
    this.urlPrefix = urlPrefix.replaceAll("/+$", "");
    try {
      Files.createDirectories(this.uploadsRoot);
    } catch (IOException e) {
      throw new IllegalStateException("cannot create uploads dir: " + this.uploadsRoot, e);
    }
  }

  /**
   * Guarda el audio, lo transcribe y postea la nota de voz como mensaje del
   * cliente. Devuelve el mensaje creado (con la transcripción como text).
   */
  public LeadMessageResponse receiveFromCustomer(Long leadId, String token, MultipartFile file) {
    Lead lead = leadRepository.findById(leadId)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "lead not found"));
    if (lead.getAccessToken() == null || token == null || !lead.getAccessToken().equals(token)) {
      throw new ResponseStatusException(HttpStatus.FORBIDDEN, "invalid token");
    }
    validate(file);

    byte[] bytes;
    try {
      bytes = file.getBytes();
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "no se pudo leer el audio");
    }

    String audioUrl = store(leadId, bytes, file.getContentType(), file.getOriginalFilename());

    String filename = "nota-de-voz" + extensionFor(file.getContentType(), file.getOriginalFilename());
    Optional<String> transcript = transcriptionService.transcribe(
        bytes, filename, file.getContentType() == null ? "audio/webm" : file.getContentType());

    if (transcript.isPresent()) {
      LeadMessageResponse saved =
          messageService.postVoiceNoteFromCustomer(leadId, token, transcript.get(), audioUrl);
      // Mismo camino que el texto tipeado: relay al proveedor aceptado o
      // turno del agente — la transcripción ES el mensaje del cliente.
      messageRouter.route(leadId, transcript.get());
      return saved;
    }

    // Fallback determinista: la nota queda en el chat (audible para el
    // proveedor) y Fixy pide el reintento con honestidad — sin turno de LLM
    // sobre un texto que no existe.
    LeadMessageResponse saved =
        messageService.postVoiceNoteFromCustomer(leadId, token, FALLBACK_TEXT, audioUrl);
    messageService.postFromAgent(leadId, RETRY_REPLY);
    return saved;
  }

  private String store(Long leadId, byte[] bytes, String contentType, String originalName) {
    String extension = extensionFor(contentType, originalName);
    String relative = "lead-" + leadId + "/" + randomHex(8) + extension;
    Path target = uploadsRoot.resolve(relative).normalize();
    if (!target.startsWith(uploadsRoot)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid filename");
    }
    try {
      Files.createDirectories(target.getParent());
      Files.write(target, bytes);
    } catch (IOException e) {
      throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "no se pudo guardar el audio");
    }
    return urlPrefix + "/" + relative;
  }

  private void validate(MultipartFile file) {
    if (file == null || file.isEmpty()) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audio vacio");
    }
    if (file.getSize() > MAX_BYTES) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "audio demasiado grande (max 10 MB)");
    }
    String ct = file.getContentType() == null ? "" : file.getContentType().toLowerCase(Locale.ROOT);
    // Los navegadores agregan parámetros (ej. "audio/webm;codecs=opus") — se
    // valida por prefijo base.
    String base = ct.split(";")[0].trim();
    if (!ALLOWED_CT_PREFIXES.contains(base)) {
      throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "formato de audio no permitido");
    }
  }

  private String extensionFor(String contentType, String original) {
    String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].trim();
    return switch (ct) {
      case "audio/webm", "video/webm" -> ".webm";
      case "audio/ogg" -> ".ogg";
      case "audio/mp4", "video/mp4", "audio/m4a", "audio/x-m4a", "audio/aac" -> ".m4a";
      case "audio/mpeg" -> ".mp3";
      case "audio/wav" -> ".wav";
      case "audio/3gpp" -> ".3gp";
      default -> {
        if (original == null) yield ".webm";
        int dot = original.lastIndexOf('.');
        yield dot >= 0 ? original.substring(dot).toLowerCase(Locale.ROOT) : ".webm";
      }
    };
  }

  private String randomHex(int bytes) {
    byte[] buf = new byte[bytes];
    random.nextBytes(buf);
    return HexFormat.of().formatHex(buf);
  }
}
