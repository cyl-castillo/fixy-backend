package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.PushSubscriptionRepository;
import com.fixy.backend.service.PushNotificationService;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Web Push (Ola UX): notificaciones reales del navegador aunque la pestaña
 * esté cerrada. Mismo patrón que {@code TelegramNotifyServiceTest}: HttpServer
 * embebido del JDK como mock del push service del navegador (el endpoint de
 * la suscripción apunta a localhost), cero red real.
 *
 * Claves VAPID de test y claves de suscripción (p256dh/auth) generadas una
 * vez con la propia librería {@code nl.martijndwars:web-push} (EC secp256r1
 * válidas, requeridas por el cifrado real de la lib) y fijadas acá — no hay
 * forma de generarlas "fake" porque la lib valida el formato/curva antes de
 * cifrar.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.push.vapid-public-key=BIgTEzJ-hmuHgo5VSU9gDge3EfvM9zfNBd81XdwQZ2mJJONEpWsRdPfMVFlkjIu7NkkyU9kgHAoMFPDPby4JiMQ",
    "fixy.push.vapid-private-key=Qf4h3DlXM2gjkIVCcIhaY5n8I7AkX9S9mnjNOlb_fGs",
    "fixy.push.subject=mailto:test@fixy.com.uy"
})
class PushNotificationServiceTest {

  private static final String TEST_P256DH =
      "BB29wUlDFQ8sH480p60J6Yz_0ujLqnpHGj0AD0NVdx0VRv3j0KvllMOv0D1Duq79pvCK33zyr1wk83r0x6u-oOk";
  private static final String TEST_AUTH = "_xgFH_QZjDuldDwyt4zTTw";

  @Autowired
  private PushNotificationService pushNotificationService;

  @Autowired
  private PushSubscriptionRepository repository;

  private static HttpServer server;
  private static final BlockingQueue<String> receivedPaths = new ArrayBlockingQueue<>(10);
  private static final AtomicInteger nextStatus = new AtomicInteger(201);

  private static synchronized HttpServer ensureServer() throws IOException {
    if (server == null) {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18766), 0);
      server.createContext("/", exchange -> {
        exchange.getRequestBody().readAllBytes();
        receivedPaths.offer(exchange.getRequestURI().getPath());
        int status = nextStatus.get();
        exchange.sendResponseHeaders(status, -1);
        exchange.close();
      });
      server.start();
    }
    return server;
  }

  {
    try {
      ensureServer();
    } catch (IOException ex) {
      throw new RuntimeException(ex);
    }
  }

  @AfterEach
  void reset() {
    receivedPaths.clear();
    nextStatus.set(201);
  }

  private PushSubscription persistSubscriptionForLead(Long leadId, String endpointPath) {
    return persist(leadId, null, endpointPath);
  }

  private PushSubscription persistSubscriptionForProvider(Long providerId, String endpointPath) {
    return persist(null, providerId, endpointPath);
  }

  private PushSubscription persist(Long leadId, Long providerId, String endpointPath) {
    PushSubscription sub = new PushSubscription();
    sub.setLeadId(leadId);
    sub.setProviderId(providerId);
    sub.setEndpoint("http://127.0.0.1:18766" + endpointPath);
    sub.setP256dh(TEST_P256DH);
    sub.setAuth(TEST_AUTH);
    return repository.save(sub);
  }

  private String awaitOneRequest() {
    String path;
    try {
      path = receivedPaths.poll(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    assertThat(path).as("el push service debería haber recibido un POST").isNotNull();
    return path;
  }

  @Test
  void isEnabled_withTestVapidKeys() {
    assertThat(pushNotificationService.isEnabled()).isTrue();
    assertThat(pushNotificationService.vapidPublicKey())
        .isEqualTo("BIgTEzJ-hmuHgo5VSU9gDge3EfvM9zfNBd81XdwQZ2mJJONEpWsRdPfMVFlkjIu7NkkyU9kgHAoMFPDPby4JiMQ");
  }

  @Test
  void saveSubscriptionForLead_persists() {
    pushNotificationService.saveSubscriptionForLead(9001L, "http://127.0.0.1:18766/lead-save", TEST_P256DH, TEST_AUTH);

    List<PushSubscription> subs = repository.findByLeadId(9001L);
    assertThat(subs).hasSize(1);
    assertThat(subs.get(0).getEndpoint()).isEqualTo("http://127.0.0.1:18766/lead-save");
    assertThat(subs.get(0).getP256dh()).isEqualTo(TEST_P256DH);
  }

  @Test
  void saveSubscriptionForLead_sameEndpointTwice_doesNotDuplicate() {
    pushNotificationService.saveSubscriptionForLead(9002L, "http://127.0.0.1:18766/lead-dup", TEST_P256DH, TEST_AUTH);
    pushNotificationService.saveSubscriptionForLead(9002L, "http://127.0.0.1:18766/lead-dup", TEST_P256DH, TEST_AUTH);

    assertThat(repository.findByLeadId(9002L)).hasSize(1);
  }

  @Test
  void saveSubscriptionForProvider_persists() {
    pushNotificationService.saveSubscriptionForProvider(9003L, "http://127.0.0.1:18766/prov-save", TEST_P256DH, TEST_AUTH);

    List<PushSubscription> subs = repository.findByProviderId(9003L);
    assertThat(subs).hasSize(1);
  }

  @Test
  void notifyLeadHasNews_sendsRealEncryptedPushToEndpoint() {
    persistSubscriptionForLead(9010L, "/lead-news-endpoint");

    pushNotificationService.notifyLeadHasNews(9010L, "Fixy: tenés novedades", "El proveedor confirmó el horario");

    String path = awaitOneRequest();
    assertThat(path).isEqualTo("/lead-news-endpoint");
  }

  @Test
  void notifyProvider_sendsRealEncryptedPushToEndpoint() {
    persistSubscriptionForProvider(9011L, "/provider-endpoint");

    pushNotificationService.notifyProvider(9011L, "abc123token", "Cliente te escribió", "Para el viernes");

    String path = awaitOneRequest();
    assertThat(path).isEqualTo("/provider-endpoint");
  }

  @Test
  void deadSubscription_isDeletedOn410Gone() {
    PushSubscription sub = persistSubscriptionForLead(9020L, "/dead-endpoint");
    nextStatus.set(410);

    pushNotificationService.notifyLeadHasNews(9020L, "titulo", "cuerpo");

    awaitOneRequest();
    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> assertThat(repository.findById(sub.getId())).isEmpty());
  }

  @Test
  void deadSubscription_isDeletedOn404NotFound() {
    PushSubscription sub = persistSubscriptionForLead(9021L, "/dead-endpoint-404");
    nextStatus.set(404);

    pushNotificationService.notifyLeadHasNews(9021L, "titulo", "cuerpo");

    awaitOneRequest();
    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> assertThat(repository.findById(sub.getId())).isEmpty());
  }

  @Test
  void liveSubscription_survivesSuccessfulSend() {
    PushSubscription sub = persistSubscriptionForLead(9022L, "/live-endpoint");
    nextStatus.set(201);

    pushNotificationService.notifyLeadHasNews(9022L, "titulo", "cuerpo");

    awaitOneRequest();
    // Da tiempo a que un eventual delete indebido llegara antes de afirmar que sigue viva.
    try {
      Thread.sleep(300);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }
    assertThat(repository.findById(sub.getId())).isPresent();
  }

  @Test
  void notifyLeadHasNews_noSubscription_doesNothing() throws InterruptedException {
    pushNotificationService.notifyLeadHasNews(9099L, "titulo", "cuerpo");

    String path = receivedPaths.poll(800, TimeUnit.MILLISECONDS);
    assertThat(path).as("sin suscripción no debería llamar al push service").isNull();
  }
}
