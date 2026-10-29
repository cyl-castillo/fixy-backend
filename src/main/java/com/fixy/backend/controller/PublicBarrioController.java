package com.fixy.backend.controller;

import com.fixy.backend.dto.BarrioDtos.ClosureResponse;
import com.fixy.backend.dto.BarrioDtos.CreateResponse;
import com.fixy.backend.dto.BarrioDtos.PublicReport;
import com.fixy.backend.dto.BarrioDtos.Stats;
import com.fixy.backend.service.BarrioService;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/** Superficie pública de Fixy Barrio (B1). Público vía {@code /api/public/**}. Nunca devuelve teléfono ni descripción. */
@RestController
@RequestMapping("/api/public/barrio")
public class PublicBarrioController {

  private final BarrioService barrio;

  public PublicBarrioController(BarrioService barrio) {
    this.barrio = barrio;
  }

  @GetMapping("/reports")
  public List<PublicReport> list(@RequestParam(value = "status", required = false) String status) {
    return barrio.listPublic(status);
  }

  @GetMapping("/reports/{id}")
  public PublicReport get(@PathVariable Long id) {
    return barrio.getPublic(id);
  }

  @GetMapping("/stats")
  public Stats stats() {
    return barrio.stats();
  }

  @PostMapping(value = "/reports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public CreateResponse create(
      @RequestParam(value = "category", required = false) String category,
      @RequestParam(value = "lat", required = false) Double lat,
      @RequestParam(value = "lng", required = false) Double lng,
      @RequestParam(value = "photo", required = false) MultipartFile photo,
      @RequestParam(value = "description", required = false) String description,
      @RequestParam(value = "phone", required = false) String phone,
      HttpServletRequest request
  ) {
    return barrio.createFromWeb(clientIp(request), category, lat, lng, photo, description, phone);
  }

  @PostMapping(value = "/reports/{id}/closure", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
  @ResponseStatus(HttpStatus.CREATED)
  public ClosureResponse closure(
      @PathVariable Long id,
      @RequestParam(value = "photo", required = false) MultipartFile photo,
      @RequestParam(value = "comment", required = false) String comment,
      @RequestParam(value = "lat", required = false) Double lat,
      @RequestParam(value = "lng", required = false) Double lng,
      @RequestParam(value = "accuracy", required = false) Double accuracy,
      HttpServletRequest request
  ) {
    return barrio.submitClosure(clientIp(request), id, photo, comment, lat, lng, accuracy);
  }

  /**
   * Detrás de nginx (y del túnel de Cloudflare) el remoteAddr es loopback: se toma el primer hop
   * NO loopback de X-Forwarded-For leyendo de derecha a izquierda (lo que agregó nuestra infra;
   * lo que el cliente escriba a la izquierda no cuenta).
   */
  static String clientIp(HttpServletRequest request) {
    String remote = request.getRemoteAddr();
    if (isLoopback(remote)) {
      String xff = request.getHeader("X-Forwarded-For");
      if (xff != null && !xff.isBlank()) {
        String[] hops = xff.split(",");
        for (int i = hops.length - 1; i >= 0; i--) {
          String hop = hops[i].trim();
          if (!hop.isEmpty() && !isLoopback(hop)) {
            return hop;
          }
        }
      }
    }
    return remote;
  }

  private static boolean isLoopback(String ip) {
    return ip != null && (ip.startsWith("127.") || ip.equals("::1") || ip.equals("0:0:0:0:0:0:0:1"));
  }
}
