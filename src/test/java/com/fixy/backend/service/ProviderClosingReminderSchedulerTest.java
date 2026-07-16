package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import org.awaitility.Awaitility;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

/**
 * Caso real (2026-07-16): Barométrica Nueva Era hizo el trabajo del lead
 * #105, cobró $2500 y nunca marcó "Completado" — Carlos tuvo que perseguirlo
 * por WhatsApp y se cerró por ops. Este scheduler avisa antes de que haga
 * falta perseguir a nadie: recordatorio push al proveedor a las 24h,
 * escalamiento a Telegram/ops a las 48h.
 *
 * provider-hours=24 / ops-hours=48 se dejan en su default; los leads se
 * envejecen por SQL nativo sobre el evento PROVIDER_ACCEPTED (igual patrón
 * que LeadClosingSchedulerTest envejece PROVIDER_STATUS_CHANGE) ya que Lead
 * no expone setCreatedAt y el timestamp de referencia de este scheduler es
 * el evento de asignación, no el de creación del lead.
 *
 * Sin @Transactional (a diferencia de ReengagementSchedulerTest): el aviso a
 * ops corre @Async en TelegramNotifyService, y un rollback de la transacción
 * del test borraría el Lead antes de que ese hilo pudiera insertar el evento
 * (viola la FK). Mismo motivo por el que TelegramNotifyServiceTest tampoco
 * usa @Transactional. Cada test usa sus propios leads, así que no colisiona
 * con la idempotencia (evento por lead) entre tests de la misma clase.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.closing-reminder.provider-hours=24",
    "fixy.closing-reminder.ops-hours=48",
    "fixy.telegram.bot-token=test-bot-token",
    "fixy.telegram.chat-id=123456789",
    "fixy.telegram.base-url=http://127.0.0.1:18767",
    "fixy.public-app-base-url=https://www.fixy.com.uy"
})
class ProviderClosingReminderSchedulerTest {

  @Autowired private ProviderClosingReminderScheduler scheduler;
  @Autowired private LeadRepository leadRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private JdbcTemplate jdbcTemplate;

  private static HttpServer server;
  private static final BlockingQueue<String> receivedBodies = new ArrayBlockingQueue<>(10);

  private static synchronized HttpServer ensureServer() throws IOException {
    if (server == null) {
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", 18767), 0);
      server.createContext("/", exchange -> {
        String body = new String(exchange.getRequestBody().readAllBytes());
        receivedBodies.offer(body);
        String response = "{\"ok\":true,\"result\":{\"message_id\":1}}";
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, response.getBytes().length);
        exchange.getResponseBody().write(response.getBytes());
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
  void drainQueue() {
    receivedBodies.clear();
  }

  private Provider persistProvider(String name) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099888777");
    provider.setCategories("barometrica");
    provider.setPrimaryZone("Solymar");
    provider.setStatus(ProviderStatus.AVAILABLE);
    return providerRepository.save(provider);
  }

  private Lead persistAssignedLead(String problem, Provider provider, LeadStatus status) {
    Lead lead = new Lead();
    lead.setProblem(problem);
    lead.setChannel("web-chat");
    lead.setDetectedCategory("barometrica");
    lead.setLocation("Solymar");
    lead.setStatus(status);
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setAccessToken("tok-closing-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  /** Envejece el evento PROVIDER_ACCEPTED (timestamp de referencia del
   * scheduler) tantas horas atrás como haga falta, calcando el patrón de
   * ReengagementSchedulerTest para leads viejos. Usa JdbcTemplate (no
   * EntityManager.createNativeQuery) porque esta clase no es @Transactional
   * — el aviso a ops corre @Async y necesita ver el dato ya commiteado; un
   * update vía EntityManager fuera de una transacción JPA activa falla con
   * TransactionRequiredException. */
  private void backdateAssignmentEvent(Lead lead, long hoursAgo) {
    jdbcTemplate.update(
        "UPDATE lead_events SET created_at = ? WHERE lead_id = ? AND type = 'PROVIDER_ACCEPTED'",
        OffsetDateTime.now().minusHours(hoursAgo), lead.getId());
  }

  @Test
  void remindsProviderAt24hOnlyOnce() {
    Provider provider = persistProvider("Nueva Era");
    Lead lead = persistAssignedLead("caño roto", provider, LeadStatus.ASSIGNED);
    leadEventRepository.save(newAcceptedEvent(lead));
    backdateAssignmentEvent(lead, 25);

    int first = scheduler.processOnce();
    int second = scheduler.processOnce();

    assertThat(first).isEqualTo(1);
    assertThat(second).isEqualTo(0);
    assertThat(leadEventRepository
        .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "PROVIDER_CLOSING_REMINDED"))
        .hasSize(1);
  }

  @Test
  void notifiesOpsAt48hOnlyOnce() throws InterruptedException {
    Provider provider = persistProvider("Nueva Era");
    Lead lead = persistAssignedLead("caño roto", provider, LeadStatus.IN_PROGRESS);
    leadEventRepository.save(newAcceptedEvent(lead));
    backdateAssignmentEvent(lead, 49);

    scheduler.processOnce();
    String body = awaitOneMessage();
    assertThat(body).contains("Trabajo #" + lead.getId());
    assertThat(body).contains("barométrica en Solymar");
    assertThat(body).contains("Nueva Era");
    assertThat(body).contains("48h sin cerrar");

    Awaitility.await().atMost(Duration.ofSeconds(5)).pollInterval(Duration.ofMillis(50))
        .untilAsserted(() -> assertThat(leadEventRepository
            .findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), "OPS_NOTIFIED_STALE_JOB"))
            .isNotEmpty());

    scheduler.processOnce();
    String second = receivedBodies.poll(1500, TimeUnit.MILLISECONDS);
    assertThat(second).as("un trabajo viejo = un aviso a ops").isNull();
  }

  @Test
  void completedDisputedSmokeAndUnassignedLeadsAreSkipped() throws InterruptedException {
    Provider provider = persistProvider("Nueva Era");

    Lead completed = persistAssignedLead("ya cerrado", provider, LeadStatus.COMPLETED);
    leadEventRepository.save(newAcceptedEvent(completed));
    backdateAssignmentEvent(completed, 49);

    Lead disputed = persistAssignedLead("disputado", provider, LeadStatus.IN_PROGRESS);
    disputed.setDisputed(true);
    leadRepository.save(disputed);
    leadEventRepository.save(newAcceptedEvent(disputed));
    backdateAssignmentEvent(disputed, 49);

    Lead smoke = persistAssignedLead("[smoke] prueba", provider, LeadStatus.ASSIGNED);
    leadEventRepository.save(newAcceptedEvent(smoke));
    backdateAssignmentEvent(smoke, 49);

    Lead unassigned = new Lead();
    unassigned.setProblem("sin proveedor");
    unassigned.setChannel("web-chat");
    unassigned.setStatus(LeadStatus.ASSIGNED);
    unassigned.setAccessToken("tok-closing-noprov-" + System.nanoTime());
    leadRepository.save(unassigned);

    Lead fresh = persistAssignedLead("recien asignado", provider, LeadStatus.ASSIGNED);
    leadEventRepository.save(newAcceptedEvent(fresh)); // sin envejecer: <24h

    int actions = scheduler.processOnce();

    assertThat(actions).isEqualTo(0);
    String opsMsg = receivedBodies.poll(1500, TimeUnit.MILLISECONDS);
    assertThat(opsMsg).as("ninguno de estos leads debería avisar a ops").isNull();
  }

  private com.fixy.backend.model.LeadEvent newAcceptedEvent(Lead lead) {
    com.fixy.backend.model.LeadEvent event = new com.fixy.backend.model.LeadEvent();
    event.setLead(lead);
    event.setType("PROVIDER_ACCEPTED");
    event.setActor("provider");
    event.setMessage("Nueva Era aceptó el lead");
    return event;
  }

  private String awaitOneMessage() {
    String body;
    try {
      body = receivedBodies.poll(5, TimeUnit.SECONDS);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
      throw new RuntimeException(e);
    }
    assertThat(body).as("Telegram debería haber recibido un POST").isNotNull();
    return body;
  }
}
