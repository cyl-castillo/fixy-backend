package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.LeadMatchingService;
import com.fixy.backend.service.LeadTimelineService;
import com.fixy.backend.service.WhatsAppService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Punta a punta del rastro del wamid (incidente 2026-10-08): al contactar al
 * técnico por plantilla, el matching guarda el wamid que devolvió Meta en un
 * evento WHATSAPP_SENT; cuando Meta reporta {@code failed} por el webhook
 * para ese mismo wamid, el lead correcto recibe WHATSAPP_DELIVERY_FAILED.
 *
 * WhatsAppService mockeado con @MockitoBean (misma configuración de contexto
 * que WhatsAppWebhookCustomerFlowTest para compartir el contexto cacheado):
 * cero llamadas de red, se simula la respuesta "aceptado, id=wamid...".
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true"
})
@Transactional
class WhatsAppSentWamidTimelineTest {

  private static final String WAMID = "wamid.HBgLNTk4OTM2NDA5ODMVAgARGBI0NDU1QjM0QzU1RjI4RjM5RDcA";

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadMatchingService leadMatchingService;
  @Autowired private LeadTimelineService leadTimelineService;

  @MockitoBean private WhatsAppService whatsappService;

  private Provider persistProvider(String name, String phone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone(phone);
    provider.setWhatsappNumber(phone);
    // decoracion_fiestas: sin proveedores seed, el único candidato es este.
    provider.setCategories("decoracion_fiestas");
    provider.setPrimaryZone("Solymar");
    provider.setCoverageZones("Solymar");
    provider.setCity("Ciudad de la Costa");
    provider.setDepartment("Canelones");
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setVerificationStatus(ProviderVerificationStatus.BASIC_CHECKED);
    return providerRepository.save(provider);
  }

  private Lead persistReadyLead() {
    Lead lead = new Lead();
    lead.setName("Vecina de prueba");
    lead.setPhone("099556001");
    lead.setProblem("Decoración para un cumpleaños el sábado");
    lead.setChannel("web");
    lead.setDetectedCategory("decoracion_fiestas");
    lead.setLocation("Solymar");
    lead.setUrgency("media");
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    lead.setAccessToken("token-wamid-timeline");
    return leadRepository.save(lead);
  }

  @Test
  void laPlantillaDejaElWamidEnLaTimeline_yElFailedDelWebhookLlegaAlLeadCorrecto() throws Exception {
    when(whatsappService.isEnabled()).thenReturn(true);
    when(whatsappService.sendTemplateReturningId(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(WAMID);
    Provider provider = persistProvider("Decoradora Wamid", "099720001");
    Lead lead = persistReadyLead();

    assertThat(leadMatchingService.matchNow(lead)).isTrue();

    List<LeadEvent> sent = leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
        lead.getId(), LeadTimelineService.WHATSAPP_SENT);
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).getActor()).isEqualTo("system");
    assertThat(sent.get(0).getMessage())
        .contains(provider.getName())
        .contains("(wamid=" + WAMID + ")");
    assertThat(leadTimelineService.findLeadByWhatsAppMessageId(WAMID))
        .map(Lead::getId).hasValue(lead.getId());

    String failed = """
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
                  "recipient_id": "59899720001",
                  "errors": [{
                    "code": 131026,
                    "title": "Message undeliverable",
                    "message": "Message undeliverable.",
                    "error_data": {"details": "Message Undeliverable."}
                  }]
                }]
              },
              "field": "messages"
            }]
          }]
        }
        """.formatted(WAMID);
    mockMvc.perform(post("/api/webhooks/whatsapp")
            .contentType(MediaType.APPLICATION_JSON)
            .content(failed))
        .andExpect(status().isOk());

    List<LeadEvent> deliveryFailed = leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
        lead.getId(), LeadTimelineService.WHATSAPP_DELIVERY_FAILED);
    assertThat(deliveryFailed).hasSize(1);
    assertThat(deliveryFailed.get(0).getMessage()).contains("131026").contains("Message undeliverable");
  }

  @Test
  void sinWamid_noSeRegistraWhatsappSent() {
    when(whatsappService.isEnabled()).thenReturn(true);
    when(whatsappService.sendTemplateReturningId(anyString(), anyString(), anyString(), anyList()))
        .thenReturn(null);
    persistProvider("Decoradora Sin Wamid", "099720002");
    Lead lead = persistReadyLead();

    assertThat(leadMatchingService.matchNow(lead)).isTrue();

    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
        lead.getId(), LeadTimelineService.WHATSAPP_SENT)).isEmpty();
    assertThat(leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(
        lead.getId(), "PROVIDER_CONTACTED")).hasSize(1);
  }
}
