package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.repository.PushSubscriptionRepository;
import com.fixy.backend.service.PushNotificationService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sin claves VAPID (fixy.push.vapid-public-key / vapid-private-key vacíos,
 * default de application.yml y del entorno de test): el servicio queda
 * disabled. Guardar una suscripción sigue funcionando (el frontend no
 * debería mostrar el opt-in sin clave pública, pero si igual llegara un
 * POST no debe romper); notificar es no-op seguro sin intentar red.
 */
@SpringBootTest
class PushNotificationServiceDisabledTest {

  @Autowired
  private PushNotificationService pushNotificationService;

  @Autowired
  private PushSubscriptionRepository repository;

  @Test
  void disabledByDefault_withoutVapidKeys() {
    assertThat(pushNotificationService.isEnabled()).isFalse();
    assertThat(pushNotificationService.vapidPublicKey()).isBlank();
  }

  @Test
  void withoutVapidKeys_notifyDoesNotThrowAndDoesNothing() {
    pushNotificationService.saveSubscriptionForLead(8001L, "https://example.com/ep", "p256dh-fake", "auth-fake");
    assertThat(repository.findByLeadId(8001L)).hasSize(1);

    // No debe lanzar excepción ni intentar red real: sin claves VAPID no hay
    // forma de cifrar el payload, así que directamente no se manda nada.
    pushNotificationService.notifyLeadHasNews(8001L, "titulo", "cuerpo");
    pushNotificationService.notifyProvider(1L, "tok", "titulo", "cuerpo");
  }
}
