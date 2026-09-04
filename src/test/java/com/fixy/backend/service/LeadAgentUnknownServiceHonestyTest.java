package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.ServiceCategory;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * Mejora diaria 2026-09-03. Dato: de los cinco últimos pedidos chat-first de
 * prod, cuatro murieron en el camino del pedido que no se entiende — un
 * carpintero para un sillón (#261), limpiar un parrillero (#262), un flete
 * para una mudanza (#263) y armar un ropero (#264). Ninguno es ambiguo para
 * una persona: los cuatro son oficios que Fixy no tiene. El bucle ya se había
 * cortado (7415361), pero lo que el vecino leía al final era "Te paso con una
 * persona de Fixy, en breve te contactan" — y ese contacto no existe: 26 de
 * los 27 pedidos abiertos no tienen teléfono y ningún humano contestó nunca
 * uno de estos chats. Prometer lo que no se puede cumplir es la misma deuda
 * que ya se pagó en el camino del rechazo (15b3992) y en el de la zona no
 * reconocida (c338bcb); este es el tercer camino.
 *
 * Montaje idéntico al de LeadAgentUnclassifiedLoopTest (y al de prod): agente
 * encendido, LLM sin credenciales, reengagement apagado. Sin @Transactional
 * porque respondToCustomerAsync corre @Async en otro hilo.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token=",
    "fixy.reengagement.enabled=false"
})
class LeadAgentUnknownServiceHonestyTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @MockitoBean private TelegramNotifyService telegramNotifyService;

  private Lead newChatLead(String tokenSuffix) {
    Lead lead = new Lead();
    lead.setProblem("(pendiente)");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setUrgency("baja");
    lead.setReadyForMatching(false);
    lead.setNotes("");
    lead.setAccessToken("tok-unknown-service-" + tokenSuffix + "-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  /** El caso del lead #264: "armar un ropero" no es ningún oficio del catálogo. */
  @Test
  void unknownService_showsWhatFixyActuallyCovers_insteadOfPromisingAHumanCall() throws Exception {
    Lead lead = newChatLead("ropero");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token, "servicio de armado de roperos");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    leadMessageService.postFromCustomer(leadId, token,
        "necesito armar un ropero, un armario y necesito ubicar una persona que lo haga");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    String reply = lastAgentText(leadId);
    assertThat(reply).as("no se promete un contacto humano que no va a ocurrir")
        .doesNotContain("en breve te contactan");
    assertThat(reply).as("se admite lo único cierto: no se entendió")
        .contains("no te terminé de entender");
    assertThat(reply).as("y se le deja la puerta abierta para corregirse")
        .contains("decime cuál");

    // El aviso a ops no cambió: el escalamiento sigue saliendo por Telegram.
    verify(telegramNotifyService, times(1)).notifyEscalation(any(Lead.class), anyString(), anyString());

    // Y el pedido queda escrito con las palabras del vecino (no se pierde).
    Lead saved = leadRepository.findById(leadId).orElseThrow();
    assertThat(saved.getProblem()).contains("ropero");
  }

  /**
   * La lista sale del enum, no de un texto a mano: la promesa es que sumar
   * una categoría MVP no puede dejar este mensaje mintiendo. Se verifica
   * contra TODAS las categorías MVP, no contra un par elegido.
   */
  @Test
  void coverageSentence_namesEveryMvpCategory_fromTheSingleSource() {
    String reply = LeadAgentService.whatFixyCoversReply();
    assertThat(ServiceCategory.MVP_LABELS).isNotEmpty();
    for (String label : ServiceCategory.MVP_LABELS) {
      assertThat(reply).as("falta la categoría MVP '%s'", label).contains(label);
    }
    // Y NO nombra las que Fixy no cubre operativamente hoy.
    for (ServiceCategory category : ServiceCategory.values()) {
      if (!category.isMvp() && category != ServiceCategory.OTRO) {
        assertThat(reply).as("no debe ofrecer '%s', que no es MVP", category.label())
            .doesNotContain(category.label());
      }
    }
  }

  private String lastAgentText(Long leadId) {
    List<LeadMessage> all = messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    for (int i = all.size() - 1; i >= 0; i--) {
      if (!"customer".equals(all.get(i).getSender())) {
        return all.get(i).getText();
      }
    }
    return null;
  }
}
