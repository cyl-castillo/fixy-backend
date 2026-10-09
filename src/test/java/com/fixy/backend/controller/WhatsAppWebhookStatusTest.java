package com.fixy.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadTimelineService;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code statuses[]} del webhook de WhatsApp Cloud API (incidente 2026-10-08:
 * la plantilla al técnico salió "OK id=wamid..." y nunca llegó al celular;
 * el controller descartaba los statuses, así que no había forma de ver el
 * porqué). Payloads con el formato real de Meta ("Webhooks: statuses").
 *
 * Sin firma: {@code fixy.whatsapp.app-secret} está vacío en test, igual que
 * en el resto de los tests del webhook — la validación HMAC no es lo que se
 * prueba acá.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WhatsAppWebhookStatusTest {

  private static final String WAMID = "wamid.HBgLNTk4OTM2NDA5ODMVAgARGBI3QzA2RkY0QkY2RjI4RjM5RDcA";
  private static final String RECIPIENT = "59893640983";

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadTimelineService leadTimelineService;

  private Logger controllerLogger;
  private Level previousLevel;
  private ListAppender<ILoggingEvent> logs;

  @BeforeEach
  void captureControllerLogs() {
    controllerLogger = (Logger) LoggerFactory.getLogger(WhatsAppWebhookController.class);
    previousLevel = controllerLogger.getLevel();
    controllerLogger.setLevel(Level.INFO);
    logs = new ListAppender<>();
    logs.start();
    controllerLogger.addAppender(logs);
  }

  @AfterEach
  void releaseControllerLogs() {
    controllerLogger.detachAppender(logs);
    controllerLogger.setLevel(previousLevel);
  }

  // ---------------------------------------------------------------- payloads

  /** Formato real de Meta para un status no-failed (incluye conversation y
   * pricing, que el controller debe ignorar sin romperse). */
  private static String deliveredPayload(String wamid, String recipient) {
    return """
        {
          "object": "whatsapp_business_account",
          "entry": [{
            "id": "WABA_ID",
            "changes": [{
              "value": {
                "messaging_product": "whatsapp",
                "metadata": {"display_phone_number": "59896251101", "phone_number_id": "PHONE_ID"},
                "statuses": [{
                  "id": "%s",
                  "status": "delivered",
                  "timestamp": "1759950000",
                  "recipient_id": "%s",
                  "conversation": {"id": "CONV_ID", "origin": {"type": "utility"}},
                  "pricing": {"billable": true, "pricing_model": "CBP", "category": "utility"}
                }]
              },
              "field": "messages"
            }]
          }]
        }
        """.formatted(wamid, recipient);
  }

  /** Formato real de Meta para failed con 131026 ("Message undeliverable"). */
  private static String failedPayload(String wamid, String recipient) {
    return """
        {
          "object": "whatsapp_business_account",
          "entry": [{
            "id": "WABA_ID",
            "changes": [{
              "value": {
                "messaging_product": "whatsapp",
                "metadata": {"display_phone_number": "59896251101", "phone_number_id": "PHONE_ID"},
                "statuses": [{
                  "id": "%s",
                  "status": "failed",
                  "timestamp": "1759950000",
                  "recipient_id": "%s",
                  "errors": [{
                    "code": 131026,
                    "title": "Message undeliverable",
                    "message": "Message undeliverable.",
                    "error_data": {"details": "Message Undeliverable."},
                    "href": "https://developers.facebook.com/docs/whatsapp/cloud-api/support/error-codes/"
                  }]
                }]
              },
              "field": "messages"
            }]
          }]
        }
        """.formatted(wamid, recipient);
  }

  /** Un status y un mensaje entrante del técnico en el MISMO change. */
  private static String mixedPayload(String wamid, String from, String text) {
    return """
        {
          "object": "whatsapp_business_account",
          "entry": [{
            "id": "WABA_ID",
            "changes": [{
              "value": {
                "messaging_product": "whatsapp",
                "metadata": {"display_phone_number": "59896251101", "phone_number_id": "PHONE_ID"},
                "statuses": [{
                  "id": "%s",
                  "status": "read",
                  "timestamp": "1759950001",
                  "recipient_id": "%s"
                }],
                "messages": [{
                  "from": "%s",
                  "id": "wamid.inbound1",
                  "timestamp": "1759950002",
                  "type": "text",
                  "text": {"body": "%s"}
                }]
              },
              "field": "messages"
            }]
          }]
        }
        """.formatted(wamid, from, from, text);
  }

  private void postWebhook(String payload) throws Exception {
    mockMvc.perform(post("/api/webhooks/whatsapp")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk());
  }

  // ---------------------------------------------------------------- fixtures

  private Provider persistProvider(String name, String phone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone(phone);
    provider.setWhatsappNumber(phone);
    provider.setCategories("decoracion_fiestas");
    provider.setPrimaryZone("Solymar");
    provider.setCoverageZones("Solymar");
    provider.setCity("Ciudad de la Costa");
    provider.setDepartment("Canelones");
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setVerificationStatus(ProviderVerificationStatus.BASIC_CHECKED);
    return providerRepository.save(provider);
  }

  private Lead persistLeadContactedBy(Provider provider) {
    Lead lead = new Lead();
    lead.setName("Vecina de prueba");
    lead.setPhone("099555" + provider.getId());
    lead.setProblem("Decoración para un cumpleaños el sábado");
    lead.setChannel("whatsapp");
    lead.setDetectedCategory("decoracion_fiestas");
    lead.setLocation("Solymar");
    lead.setUrgency("media");
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setAccessToken("token-status-" + provider.getId());
    return leadRepository.save(lead);
  }

  private List<LeadEvent> eventsOfType(Long leadId, String type) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(leadId, type);
  }

  private List<String> messagesAtLeast(Level level) {
    return logs.list.stream()
        .filter(e -> e.getLevel().isGreaterOrEqual(level))
        .map(ILoggingEvent::getFormattedMessage)
        .toList();
  }

  // ------------------------------------------------------------------- tests

  @Test
  void deliveredSoloSeLoguea_noTocaLaTimeline() throws Exception {
    Provider provider = persistProvider("Decoradora Status", "099710001");
    Lead lead = persistLeadContactedBy(provider);
    leadTimelineService.recordWhatsAppSent(lead, "Plantilla provider_lead_offer enviada por WhatsApp", WAMID);

    postWebhook(deliveredPayload(WAMID, RECIPIENT));

    assertThat(messagesAtLeast(Level.INFO))
        .anyMatch(m -> m.contains("whatsapp status id=" + WAMID)
            && m.contains("status=delivered") && m.contains("recipient=" + RECIPIENT));
    assertThat(messagesAtLeast(Level.WARN)).isEmpty();
    assertThat(eventsOfType(lead.getId(), LeadTimelineService.WHATSAPP_DELIVERY_FAILED)).isEmpty();
    assertThat(eventsOfType(lead.getId(), LeadTimelineService.WHATSAPP_SENT)).hasSize(1);
  }

  @Test
  void failedSinLeadAsociado_quedaEnWarnConElDetalleDelError() throws Exception {
    long eventsBefore = leadEventRepository.count();

    postWebhook(failedPayload("wamid.sinLead", RECIPIENT));

    List<String> warns = messagesAtLeast(Level.WARN);
    assertThat(warns).anyMatch(m -> m.contains("whatsapp status FAILED id=wamid.sinLead")
        && m.contains("recipient=" + RECIPIENT)
        && m.contains("code=131026")
        && m.contains("title=Message undeliverable")
        && m.contains("message=Message undeliverable.")
        && m.contains("details=Message Undeliverable."));
    assertThat(leadEventRepository.count()).isEqualTo(eventsBefore);
  }

  @Test
  void failedConLeadAsociado_dejaWhatsappDeliveryFailedUnaSolaVez() throws Exception {
    Provider provider = persistProvider("Decoradora Status Failed", "099710002");
    Lead lead = persistLeadContactedBy(provider);
    leadTimelineService.recordWhatsAppSent(lead,
        "Plantilla provider_lead_offer enviada por WhatsApp a " + provider.getName() + " (" + RECIPIENT + ")", WAMID);

    postWebhook(failedPayload(WAMID, RECIPIENT));

    List<LeadEvent> failed = eventsOfType(lead.getId(), LeadTimelineService.WHATSAPP_DELIVERY_FAILED);
    assertThat(failed).hasSize(1);
    LeadEvent event = failed.get(0);
    assertThat(event.getActor()).isEqualTo("system");
    assertThat(event.getMessage())
        .contains(RECIPIENT)
        .contains("131026")
        .contains("Message undeliverable")
        .contains(WAMID);

    // Meta reintenta el webhook si no le respondimos 200 a tiempo: el mismo
    // status dos veces no duplica el evento.
    postWebhook(failedPayload(WAMID, RECIPIENT));
    assertThat(eventsOfType(lead.getId(), LeadTimelineService.WHATSAPP_DELIVERY_FAILED)).hasSize(1);

    assertThat(messagesAtLeast(Level.WARN))
        .anyMatch(m -> m.contains("code=131026"))
        .anyMatch(m -> m.contains("asociado al lead " + lead.getId()));
  }

  @Test
  void statusesYMessagesEnElMismoChange_elMensajeDelTecnicoSigueLlegando() throws Exception {
    Provider provider = persistProvider("Decoradora Status Mixed", "099710003");
    Lead lead = persistLeadContactedBy(provider);

    postWebhook(mixedPayload("wamid.mixed1", provider.getPhone(), "Llego tipo 17hs"));

    assertThat(messagesAtLeast(Level.INFO))
        .anyMatch(m -> m.contains("whatsapp status id=wamid.mixed1") && m.contains("status=read"));
    List<LeadMessage> relayed = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
    assertThat(relayed).extracting(LeadMessage::getText).anyMatch(t -> t.contains("Llego tipo 17hs"));
  }

  @Test
  void statusMalformado_noRompeElRestoDelPayload() throws Exception {
    Provider provider = persistProvider("Decoradora Status Broken", "099710004");
    Lead lead = persistLeadContactedBy(provider);
    String payload = """
        {
          "entry": [{
            "changes": [{
              "value": {
                "statuses": [{"status": "failed", "errors": "no-es-un-array"}, {}],
                "messages": [{
                  "from": "%s",
                  "id": "wamid.inbound2",
                  "type": "text",
                  "text": {"body": "Voy en camino"}
                }]
              }
            }]
          }]
        }
        """.formatted(provider.getPhone());

    postWebhook(payload);

    List<LeadMessage> relayed = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
    assertThat(relayed).extracting(LeadMessage::getText).anyMatch(t -> t.contains("Voy en camino"));
  }
}
