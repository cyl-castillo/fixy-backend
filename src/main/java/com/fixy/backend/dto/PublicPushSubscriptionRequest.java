package com.fixy.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Body del alta pública de suscripción push (Fase Push-2, enganche):
 * {@code POST /api/public/push-subscriptions}, sin token — a diferencia de
 * {@link PushSubscriptionRequest} (alta de cliente/proveedor ya identificado
 * por token), esta ruta la llama la PWA directo, antes de que exista un lead
 * o un provider (visitante) o para un dispositivo que ya tenía una fila
 * (upsert por {@code endpoint}, ver {@code PushNotificationService.upsertPublicSubscription}).
 *
 * <p>{@code zone} viaja como texto libre (lo declara el cliente); el backend
 * la valida contra {@link com.fixy.backend.model.CoverageZone} y la guarda
 * null si no la reconoce — nunca rompe el alta por una zona rara.
 * {@code savedOfferIds} es opcional (null/vacío = no guardó ninguna
 * oferta).
 *
 * <p>{@code merchantToken} (Fase 5, panel self-service del comercio):
 * opcional — si viene y resuelve a un {@link com.fixy.backend.model.Business}
 * (mismo token del panel, ver {@code BusinessService.ensurePanelLink}), la
 * suscripción queda ligada a ese comercio ({@code businessId}) para que
 * {@code MerchantOfferExpiryScheduler} pueda avisarle al dueño. Un token que
 * no resuelve se ignora en silencio — nunca rompe el alta de push por un
 * token de comercio inválido o vencido.
 */
public record PublicPushSubscriptionRequest(
    @NotBlank String endpoint,
    @NotNull @Valid PushSubscriptionRequest.Keys keys,
    String zone,
    List<Long> savedOfferIds,
    String merchantToken
) {
}
