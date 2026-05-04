package com.fixy.backend.dto;

import java.util.Map;

/**
 * Resumen de dependencias del backend.
 *
 * No verifica conectividad real con servicios externos — solo si la
 * configuración mínima está presente y si la DB responde a un ping
 * trivial. La intención es que un alerting external pueda detectar
 * problemas de configuración (key sin definir, DB caída) sin gastar
 * tokens contra OpenAI.
 */
public record HealthDepsResponse(
    String status,
    String service,
    Map<String, String> dependencies
) {
}
