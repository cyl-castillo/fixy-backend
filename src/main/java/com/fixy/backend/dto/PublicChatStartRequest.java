package com.fixy.backend.dto;

/**
 * Request opcional al iniciar una conversacion chat-first.
 * Todos los campos son opcionales — el agente extrae lo que vaya
 * apareciendo a lo largo de la conversacion.
 */
public record PublicChatStartRequest(
    String name,
    String phone,
    String channel
) {
}
