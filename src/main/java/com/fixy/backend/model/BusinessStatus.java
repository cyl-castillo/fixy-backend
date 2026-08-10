package com.fixy.backend.model;

/**
 * Ciclo de vida de {@link Business}, deliberadamente separado del de
 * {@link ProviderStatus} — ver diseño en FIXY_OFERTAS_INGESTA_DESIGN.md §3.1:
 * "puede publicar una oferta" es una decisión de negocio distinta de "puede
 * recibir y trabajar un lead asignado".
 *
 * <p>{@code PENDING} no lo escribe nada todavía en este alcance (alta manual
 * por ops nace {@code ACTIVE} directo) — queda reservado para una futura
 * Opción D de autoregistro (ver diseño §2), no se borra "por si" porque ya
 * está en el modelo aprobado.
 */
public enum BusinessStatus {
  PENDING,
  ACTIVE,
  INACTIVE
}
