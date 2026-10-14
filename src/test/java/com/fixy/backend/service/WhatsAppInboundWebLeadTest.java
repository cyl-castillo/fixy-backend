package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadMessageRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * El vecino con pedido web responde la plantilla {@code customer_order_update}
 * (lead #280, 2026-10-13): su mensaje de WhatsApp (E.164, "59893640983") debe
 * caer en SU pedido (guardado como "093640983"), el lead pasa a canal
 * whatsapp, y el SÍ/NO a una propuesta de horario pendiente llega a
 * {@link LeadScheduleService#respond} sin pasar por el agente.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true"
})
@Transactional
class WhatsAppInboundWebLeadTest {

  @Autowired private WhatsAppInboundService inboundService;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private LeadMessageRepository leadMessageRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private ProviderSelfService providerSelfService;
  @Autowired private LeadScheduleService leadScheduleService;
  @Autowired private LeadMessageService leadMessageService;

  @MockitoBean private WhatsAppService whatsappService;

  @BeforeEach
  void whatsappEncendido() {
    when(whatsappService.isEnabled()).thenReturn(true);
  }

  private Lead webLead(String phone, LeadStatus status) {
    Lead lead = new Lead();
    lead.setName("Vecino inbound");
    lead.setPhone(phone);
    lead.setChannel("web-app");
    lead.setProblem("[smoke] pedido web que el vecino sigue por WhatsApp");
    lead.setDetectedCategory("aire_acondicionado");
    lead.setLocation("Solymar");
    lead.setStatus(status);
    lead.setAccessToken("tok-inbound-" + phone);
    return leadRepository.save(lead);
  }

  private Provider assignTo(Lead lead, String name) {
    Provider p = new Provider();
    p.setName(name);
    p.setPhone("0996" + Math.abs(name.hashCode() % 100000));
    p.setCategories("aire_acondicionado");
    p.setPrimaryZone("Solymar");
    p.setStatus(ProviderStatus.AVAILABLE);
    p.setAccessToken("token-" + name.replace(' ', '-'));
    p = providerRepository.save(p);
    lead.setAssignedProviderId(p.getId());
    lead.setAssignedProvider(p.getName());
    lead.setStatus(LeadStatus.ASSIGNED);
    leadRepository.save(lead);
    return p;
  }

  private List<LeadEvent> events(Lead lead, String type) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), type);
  }

  // -------------------------------------------------- matching + cambio de canal

  @Test
  void elWebhookE164CaeEnElPedidoWebGuardadoEnFormatoLocal_yElCanalPasaAWhatsapp() {
    Lead lead = webLead("093640983", LeadStatus.PROVIDER_CONTACTED);

    inboundService.handleCustomerMessage("59893640983", "hola, vi el aviso");

    Lead reloaded = leadRepository.findById(lead.getId()).orElseThrow();
    assertThat(reloaded.getChannel()).isEqualTo("whatsapp");
    assertThat(reloaded.getPhone()).isEqualTo("093640983"); // el dato guardado no se toca
    List<LeadEvent> switched = events(lead, WhatsAppInboundService.CHANNEL_SWITCHED_EVENT);
    assertThat(switched).hasSize(1);
    assertThat(switched.get(0).getActor()).isEqualTo("system");
    assertThat(leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()))
        .extracting(LeadMessage::getText).contains("hola, vi el aviso");
    // no se creó un lead nuevo para ese teléfono
    assertThat(leadRepository.findOpenWithPhoneSince(
        List.of(LeadStatus.COMPLETED, LeadStatus.CANCELLED), java.time.OffsetDateTime.now().minusDays(1)))
        .filteredOn(l -> l.getPhone() != null && l.getPhone().endsWith("93640983"))
        .hasSize(1);
  }

  @Test
  void matchingNormalizadoEnTodasLasVariantesDeTelefono() {
    String[][] cases = {
        {"+598 98 100 101", "59898100101"},
        {"098 100 102", "59898100102"},
        {"98100103", "59898100103"},
        {"59898100104", "59898100104"},
    };
    for (String[] c : cases) {
      Lead lead = webLead(c[0], LeadStatus.NEW);
      inboundService.handleCustomerMessage(c[1], "hola");
      assertThat(leadRepository.findById(lead.getId()).orElseThrow().getChannel())
          .as("telefono guardado %s", c[0]).isEqualTo("whatsapp");
    }
  }

  @Test
  void despuesDelCambio_elSiguienteMensajeSigueEnElMismoPedido_yElAgenteLeReenviaTextoLibre() {
    Lead lead = webLead("098100110", LeadStatus.PROVIDER_CONTACTED);
    inboundService.handleCustomerMessage("59898100110", "hola");
    inboundService.handleCustomerMessage("59898100110", "¿cuándo viene?");

    assertThat(events(lead, WhatsAppInboundService.CHANNEL_SWITCHED_EVENT)).hasSize(1);
    assertThat(leadMessageRepository.findByLeadIdOrderByCreatedAtAsc(lead.getId()))
        .filteredOn(m -> "customer".equals(m.getSender())).hasSize(2);

    // desde el cambio, postFromAgent reenvía por texto libre (ventana de 24 h abierta)
    leadMessageService.postFromAgent(lead.getId(), "Ya te aviso apenas confirme el técnico.");
    verify(whatsappService).sendText("098100110", "Ya te aviso apenas confirme el técnico.");
  }

  @Test
  void unPedidoTerminadoOViejoNoSeReabre_nacioUnLeadNuevoDeWhatsapp() {
    Lead terminado = webLead("098100120", LeadStatus.COMPLETED);

    inboundService.handleCustomerMessage("59898100120", "hola de nuevo");

    assertThat(leadRepository.findById(terminado.getId()).orElseThrow().getChannel()).isEqualTo("web-app");
    assertThat(leadRepository.findByPhoneAndChannelOrderByCreatedAtDesc("59898100120", "whatsapp")).hasSize(1);
  }

  // -------------------------------------------------- SÍ / NO a la propuesta

  @Test
  void siPorWhatsappConfirmaLaPropuestaPendiente() {
    Lead lead = webLead("098100130", LeadStatus.PROVIDER_CONTACTED);
    Provider carnot = assignTo(lead, "Carnot Inbound Si");
    providerSelfService.proposeSchedule(carnot, lead.getId(), "mañana de 14 a 16");
    assertThat(leadScheduleService.hasPendingProposal(lead.getId())).isTrue();

    inboundService.handleCustomerMessage("59898100130", "Sí!");

    assertThat(leadScheduleService.hasPendingProposal(lead.getId())).isFalse();
    assertThat(events(lead, LeadScheduleService.SCHEDULE_CONFIRMED_EVENT_TYPE)).hasSize(1);
    assertThat(events(lead, LeadScheduleService.SCHEDULE_CONFIRMED_EVENT_TYPE).get(0).getActor())
        .isEqualTo("customer");
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getArrivalWindow())
        .isEqualTo("mañana de 14 a 16");
    assertThat(leadRepository.findById(lead.getId()).orElseThrow().getChannel()).isEqualTo("whatsapp");
    verify(whatsappService).sendText(org.mockito.ArgumentMatchers.eq("59898100130"),
        org.mockito.ArgumentMatchers.contains("quedó confirmado: mañana de 14 a 16"));
  }

  @Test
  void noPorWhatsappRechazaLaPropuestaPendiente() {
    Lead lead = webLead("098100131", LeadStatus.PROVIDER_CONTACTED);
    Provider carnot = assignTo(lead, "Carnot Inbound No");
    providerSelfService.proposeSchedule(carnot, lead.getId(), "hoy de 18 a 20");

    inboundService.handleCustomerMessage("59898100131", "no puedo");

    assertThat(events(lead, LeadScheduleService.SCHEDULE_REJECTED_EVENT_TYPE)).hasSize(1);
    assertThat(events(lead, LeadScheduleService.SCHEDULE_CONFIRMED_EVENT_TYPE)).isEmpty();
    verify(whatsappService).sendText(org.mockito.ArgumentMatchers.eq("59898100131"), anyString());
  }

  @Test
  void unSiSinPropuestaPendienteNoTocaElHorario_yUnTextoLargoNoEsUnSi() {
    Lead lead = webLead("098100132", LeadStatus.PROVIDER_CONTACTED);
    Provider carnot = assignTo(lead, "Carnot Inbound Libre");

    inboundService.handleCustomerMessage("59898100132", "sí");
    assertThat(events(lead, LeadScheduleService.SCHEDULE_CONFIRMED_EVENT_TYPE)).isEmpty();

    providerSelfService.proposeSchedule(carnot, lead.getId(), "mañana de 14 a 16");
    inboundService.handleCustomerMessage("59898100132", "sí, pero mejor a las 5");
    assertThat(leadScheduleService.hasPendingProposal(lead.getId())).isTrue();
  }

  @Test
  void parseScheduleAnswer() {
    assertThat(WhatsAppInboundService.parseScheduleAnswer("SÍ")).isTrue();
    assertThat(WhatsAppInboundService.parseScheduleAnswer(" si. ")).isTrue();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("Dale!")).isTrue();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("De acuerdo")).isTrue();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("NO")).isFalse();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("No puedo.")).isFalse();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("no me queda bien")).isFalse();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("sí, pero a las 5")).isNull();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("no sé")).isNull();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("¿a qué hora?")).isNull();
    assertThat(WhatsAppInboundService.parseScheduleAnswer("")).isNull();
    assertThat(WhatsAppInboundService.parseScheduleAnswer(null)).isNull();
  }
}
