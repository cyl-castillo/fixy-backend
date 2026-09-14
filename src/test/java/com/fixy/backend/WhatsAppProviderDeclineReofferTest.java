package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderLeadDeclineRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * SÍ/NO del proveedor por WhatsApp con re-oferta inmediata (Refundación fase
 * 1, contrato §4 — cierra el TODO histórico de {@code
 * WhatsAppWebhookController.rejectLead}: "fase 2"). El NO del primer técnico
 * dispara, en la MISMA request, el matching sobre el siguiente candidato
 * elegible, sin esperar ningún scheduler.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class WhatsAppProviderDeclineReofferTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderLeadDeclineRepository providerLeadDeclineRepository;

  private Provider persistProvider(String name, String phone) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone(phone);
    provider.setWhatsappNumber(phone);
    // decoracion_fiestas: sin proveedores seed (ver ProviderSeedConfig) —
    // aísla el test de cualquier candidato ajeno, solo entran los dos/uno
    // que este test crea.
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
    lead.setProblem("Decoración para un cumpleaños de 15 el sábado");
    lead.setChannel("whatsapp");
    lead.setDetectedCategory("decoracion_fiestas");
    lead.setLocation("Solymar");
    lead.setUrgency("media");
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    lead.setAccessToken("token-decline-reoffer-" + provider.getId());
    return leadRepository.save(lead);
  }

  private void postProviderText(String from, String messageId, String text) throws Exception {
    String payload = """
        {
          "entry": [{
            "changes": [{
              "value": {
                "messages": [{
                  "from": "%s",
                  "id": "%s",
                  "type": "text",
                  "text": {"body": "%s"}
                }]
              }
            }]
          }]
        }
        """.formatted(from, messageId, text);
    mockMvc.perform(post("/api/webhooks/whatsapp")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isOk());
  }

  @Test
  void noDelPrimerTecnico_contactaAlSegundoEnLaMismaRequest() throws Exception {
    Provider first = persistProvider("Plomero Uno Decline", "099700001");
    Provider second = persistProvider("Plomero Dos Decline", "099700002");
    Lead lead = persistLeadContactedBy(first);

    postProviderText(first.getPhone(), "wamid.decline1", "NO puedo, estoy ocupado");

    Lead updated = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(updated.getStatus()).isEqualTo(LeadStatus.PROVIDER_CONTACTED);
    assertThat(updated.getAssignedProviderId()).isEqualTo(second.getId());

    assertThat(providerLeadDeclineRepository.existsByLeadIdAndProviderId(lead.getId(), first.getId())).isTrue();

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
    assertThat(messages).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("El primer técnico no pudo"));
  }

  @Test
  void noSinSiguienteCandidato_avisaHonestamenteYNoQuedaAsignado() throws Exception {
    Provider onlyProvider = persistProvider("Plomero Único Decline", "099700003");
    Lead lead = persistLeadContactedBy(onlyProvider);

    postProviderText(onlyProvider.getPhone(), "wamid.decline2", "NO puedo tomarlo");

    Lead updated = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(updated.getAssignedProviderId()).isNull();

    List<LeadMessage> messages = leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId());
    assertThat(messages).extracting(LeadMessage::getText)
        .anyMatch(t -> t.contains("no tengo otro libre"));
  }
}
