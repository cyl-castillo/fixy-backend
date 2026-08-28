package com.fixy.backend.model;

/**
 * Ciclo de vida de una {@link BusinessInquiry} (Fase 2, V25): nace {@code
 * ANSWERED_AUTO} (el motor respondió solo, ver {@code CatalogAnswerService})
 * o {@code ESCALATED} (sin confianza suficiente, pasa al dueño). Una
 * escalada termina en {@code ANSWERED_OWNER} (el dueño contestó, ver
 * {@code BusinessInquiryService.answerAsOwner}) o {@code EXPIRED} si nadie
 * contesta en 72h ({@code BusinessInquiryExpiryScheduler}). Sin reapertura,
 * mismo criterio que {@link OfferInquiry}.
 */
public enum BusinessInquiryStatus {
  ANSWERED_AUTO,
  ESCALATED,
  ANSWERED_OWNER,
  EXPIRED
}
