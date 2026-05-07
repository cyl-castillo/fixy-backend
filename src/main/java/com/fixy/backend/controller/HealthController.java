package com.fixy.backend.controller;

import com.fixy.backend.dto.HealthDepsResponse;
import com.fixy.backend.dto.HealthResponse;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.Map;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class HealthController {

  private final EntityManager entityManager;
  private final String openaiApiKey;
  private final String openaiModel;

  public HealthController(
      EntityManager entityManager,
      @Value("${fixy.openai.api-key:}") String openaiApiKey,
      @Value("${fixy.openai.model:}") String openaiModel
  ) {
    this.entityManager = entityManager;
    this.openaiApiKey = openaiApiKey;
    this.openaiModel = openaiModel;
  }

  @GetMapping("/health")
  public HealthResponse health() {
    return new HealthResponse("ok", "fixy-backend");
  }

  /**
   * Verificación más profunda. No llama OpenAI (no gasta tokens), solo
   * chequea presencia de configuración crítica y un ping a la DB.
   */
  @GetMapping("/health/deps")
  public HealthDepsResponse deps() {
    Map<String, String> deps = new LinkedHashMap<>();

    Map.Entry<String, String> db = pingDatabase();
    deps.put("db", db.getKey());
    deps.put("db_engine", db.getValue());
    deps.put("openai_key", openaiApiKey == null || openaiApiKey.isBlank() ? "missing" : "configured");
    deps.put("openai_model", openaiModel == null || openaiModel.isBlank() ? "missing" : openaiModel);

    boolean degraded = deps.values().stream().anyMatch(v -> v.equals("missing") || v.equals("error"));
    return new HealthDepsResponse(degraded ? "degraded" : "ok", "fixy-backend", deps);
  }

  /** @return entry(status, engine) where status ∈ {up, error}. */
  private Map.Entry<String, String> pingDatabase() {
    try {
      Object value = entityManager.createNativeQuery("SELECT 1").getSingleResult();
      String engine = entityManager
          .unwrap(org.hibernate.Session.class)
          .doReturningWork(connection -> {
            String product = connection.getMetaData().getDatabaseProductName();
            String version = connection.getMetaData().getDatabaseProductVersion();
            return product + " " + version;
          });
      String status = value != null ? "up" : "error";
      return Map.entry(status, engine != null ? engine : "unknown");
    } catch (Exception ignored) {
      return Map.entry("error", "unknown");
    }
  }
}
