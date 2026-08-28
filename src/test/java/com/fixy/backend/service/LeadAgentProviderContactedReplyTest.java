package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;

/**
 * Con proveedor ya contactado, Fixy nunca dice que lo está buscando
 * (guardia diaria 2026-08-27, lead #257 — aires en Solymar): Carnot Clima
 * había sido contactado 16:04:23, estaba escribiendo en el chat 16:05:26, y
 * el agente le contestó igual al cliente "Ya tengo proveedores disponibles en
 * tu zona, estoy buscando uno para vos". El estado del pedido no puede
 * depender de que el 8B lo lea del prompt.
 *
 * Sin @Transactional: respondToCustomerAsync corre @Async en otro hilo
 * (mismo motivo que LeadAgentFallbackRepeatTest).
 */
@SpringBootTest
@TestPropertySource(properties = {
    // Agente encendido y LLM sin credenciales → cae al fallback determinista,
    // que es justo el camino que respondió de más en el #257.
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token="
})
class LeadAgentProviderContactedReplyTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @Test
  void fixyNoDiceQueBuscaProveedorCuandoYaContactoAUno() throws Exception {
    Lead lead = nuevoLeadDeAiresConProveedorContactado();
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    // El cliente escribe mientras el proveedor está siendo contactado
    // (16:05:17 del #257: "quiero un servicio de instalación de aire").
    leadMessageService.postFromCustomer(leadId, token, "quiero un servicio de instalacion de aire acondicionado!!");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    String reply = lastAgentText(leadId);
    assertThat(reply).as("el agente contesta algo").isNotBlank();
    assertThat(LeadAgentService.claimsStillSearching(reply))
        .as("con Carnot Clima ya contactado, no se dice que se está buscando proveedor: %s", reply)
        .isFalse();
    assertThat(reply).as("la respuesta nombra al proveedor que ya tiene el pedido")
        .contains("Carnot Clima");
  }

  @Test
  void insistenciaDelClienteNoRepiteElMismoTextoNiVuelveALaBusqueda() throws Exception {
    Lead lead = nuevoLeadDeAiresConProveedorContactado();
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token, "hola buenas!!");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String primera = lastAgentText(leadId);

    // Insistencia (16:06:16 del #257, el cliente además deja su teléfono).
    leadMessageService.postFromCustomer(leadId, token, "puede escribirme al 093593529 para coordinar la instalacion");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    String segunda = lastAgentText(leadId);

    assertThat(segunda).as("no repite textual la respuesta anterior").isNotEqualTo(primera);
    assertThat(LeadAgentService.claimsStillSearching(segunda))
        .as("la insistencia tampoco reabre la búsqueda: %s", segunda)
        .isFalse();
  }

  @Test
  void conElProveedorEscribiendoEnElChatLoDiceEnLugarDeHablarDeBusqueda() throws Exception {
    Lead lead = nuevoLeadDeAiresConProveedorContactado();
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    // El proveedor ya escribió en el hilo ("Hola. Buenas tardes", 16:05:26).
    LeadMessage delProveedor = new LeadMessage();
    delProveedor.setLeadId(leadId);
    delProveedor.setSender("provider");
    delProveedor.setText("Hola. Buenas tardes");
    messageRepository.save(delProveedor);

    leadMessageService.postFromCustomer(leadId, token, "necesito instalar un split, cuando pueden venir?");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    String reply = lastAgentText(leadId);
    assertThat(LeadAgentService.claimsStillSearching(reply))
        .as("con el proveedor escribiendo, hablar de búsqueda es mentir: %s", reply)
        .isFalse();
    assertThat(reply).as("manda al cliente a contestarle al proveedor en el mismo chat")
        .contains("Carnot Clima");
  }

  private Lead nuevoLeadDeAiresConProveedorContactado() {
    Lead lead = new Lead();
    lead.setProblem("Instalación de aire acondicionado");
    lead.setChannel("web-chat");
    lead.setDetectedCategory("aires_acondicionados");
    lead.setLocation("Solymar");
    lead.setUrgency("media");
    lead.setReadyForMatching(true);
    // El estado exacto del #257 cuando el cliente escribía: proveedor
    // contactado, todavía sin aceptar.
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAssignedProviderId(13L);
    lead.setAssignedProvider("Carnot Clima");
    lead.setAccessToken("tok-257-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  private String lastAgentText(Long leadId) {
    List<LeadMessage> all = messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId);
    for (int i = all.size() - 1; i >= 0; i--) {
      if ("fixy".equals(all.get(i).getSender())) {
        return all.get(i).getText();
      }
    }
    return null;
  }
}
