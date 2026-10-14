package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadEvent;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadEventRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.annotation.Transactional;

/**
 * Plantilla {@code customer_order_update} al VECINO con pedido web (lead #280,
 * 2026-10-13): condiciones del notifier y los cuatro hooks. WhatsAppService
 * mockeado (cero red); misma configuración de contexto que
 * WhatsAppSentWamidTimelineTest para compartir el contexto cacheado.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = {
    "fixy.agent.enabled=true"
})
@Transactional
class CustomerWhatsAppNotifierTest {

  private static final String WAMID = "wamid.HBgLNTk4OTgxMDAwMDEVAgARGBI0NDU1QjM0QzU1RjI4RjM5RDcA";
  private static final String TEMPLATE = "customer_order_update";

  @Autowired private CustomerWhatsAppNotifier notifier;
  @Autowired private LeadAssignmentService leadAssignmentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private ProviderSelfService providerSelfService;
  @Autowired private LeadTimelineService timelineService;
  @Autowired private LeadRepository leadRepository;
  @Autowired private LeadEventRepository leadEventRepository;
  @Autowired private ProviderRepository providerRepository;
  @Autowired private JdbcTemplate jdbcTemplate;
  @PersistenceContext private EntityManager em;

  @MockitoBean private WhatsAppService whatsappService;

  @BeforeEach
  void whatsappEncendido() {
    when(whatsappService.isEnabled()).thenReturn(true);
    when(whatsappService.sendTemplateReturningId(anyString(), anyString(), anyString(), anyList(), any()))
        .thenReturn(WAMID);
  }

  // ------------------------------------------------------------ fixtures

  private Lead webLead(String phone) {
    Lead lead = new Lead();
    lead.setName("Vecino de prueba");
    lead.setPhone(phone);
    lead.setChannel("web-app");
    lead.setProblem("[smoke] aire acondicionado para notificar al vecino");
    lead.setDetectedCategory("aire_acondicionado");
    lead.setLocation("Solymar");
    lead.setStatus(LeadStatus.PROVIDER_CONTACTED);
    lead.setAccessToken("tok-" + phone);
    return leadRepository.save(lead);
  }

  private Provider provider(String name) {
    Provider p = new Provider();
    p.setName(name);
    p.setPhone("0997" + Math.abs(name.hashCode() % 100000));
    p.setCategories("aire_acondicionado");
    p.setPrimaryZone("Solymar");
    p.setStatus(ProviderStatus.AVAILABLE);
    p.setAccessToken("token-" + name.replace(' ', '-'));
    return providerRepository.save(p);
  }

  private Lead reserve(Lead lead, Provider provider) {
    lead.setAssignedProviderId(provider.getId());
    lead.setAssignedProvider(provider.getName());
    return leadRepository.save(lead);
  }

  private List<LeadEvent> events(Lead lead, String type) {
    return leadEventRepository.findByLeadIdAndTypeOrderByCreatedAtDesc(lead.getId(), type);
  }

  /** Verifica UN envío al número normalizado y devuelve [{{1}}, {{2}}]. */
  @SuppressWarnings("unchecked")
  private List<String> verifySingleSend(String e164, Lead lead) {
    ArgumentCaptor<List<String>> params = ArgumentCaptor.forClass(List.class);
    verify(whatsappService, times(1)).sendTemplateReturningId(
        eq(e164), eq(TEMPLATE), eq("es"), params.capture(), eq(lead.getId() + "/" + lead.getAccessToken()));
    return params.getValue();
  }

  // ------------------------------------------------------------ notifier

  @Test
  void enviaParaLeadWebConTelefono_yDejaElWamidEnLaTimeline() {
    Lead lead = webLead("098100001");

    notifier.notify(lead, "Carnot", "va en camino.");

    assertThat(verifySingleSend("59898100001", lead)).containsExactly("Carnot", "va en camino.");
    List<LeadEvent> sent = events(lead, LeadTimelineService.WHATSAPP_SENT);
    assertThat(sent).hasSize(1);
    assertThat(sent.get(0).getActor()).isEqualTo("system");
    assertThat(sent.get(0).getMessage())
        .isEqualTo("Plantilla customer_order_update enviada por WhatsApp al vecino (59898100001) (wamid=" + WAMID + ")");
    assertThat(timelineService.findLeadByWhatsAppMessageId(WAMID)).map(Lead::getId).hasValue(lead.getId());
  }

  @Test
  void leadSmokeTambienSeNotifica() {
    Lead lead = webLead("098100002");
    assertThat(lead.getProblem()).startsWith("[smoke]");

    notifier.notify(lead, "Carnot", "va en camino.");

    verifySingleSend("59898100002", lead);
  }

  @Test
  void noEnviaParaLeadDeCanalWhatsapp() {
    Lead lead = webLead("098100003");
    lead.setChannel("whatsapp");

    notifier.notify(lead, "Carnot", "va en camino.");

    verify(whatsappService, never()).sendTemplateReturningId(any(), any(), any(), any(), any());
    assertThat(events(lead, LeadTimelineService.WHATSAPP_SENT)).isEmpty();
  }

  @Test
  void noEnviaSinTelefonoOConTelefonoInvalido() {
    for (String phone : new String[] {null, "", "  ", "abc", "1234"}) {
      Lead lead = webLead("098100004");
      lead.setPhone(phone);
      notifier.notify(lead, "Carnot", "va en camino.");
    }

    verify(whatsappService, never()).sendTemplateReturningId(any(), any(), any(), any(), any());
  }

  @Test
  void noEnviaConWhatsappApagadoOFlagDeLaPlantillaApagado() {
    when(whatsappService.isEnabled()).thenReturn(false);
    Lead lead = webLead("098100005");
    notifier.notify(lead, "Carnot", "va en camino.");

    CustomerWhatsAppNotifier flagOff = new CustomerWhatsAppNotifier(
        whatsappService, timelineService, leadEventRepository, false, TEMPLATE, "es", true);
    when(whatsappService.isEnabled()).thenReturn(true);
    flagOff.notify(lead, "Carnot", "va en camino.");

    verify(whatsappService, never()).sendTemplateReturningId(any(), any(), any(), any(), any());
  }

  @Test
  void dedupeDeDiezMinutos_yVuelveAEnviarPasadaLaVentana() {
    Lead lead = webLead("098100006");

    notifier.notify(lead, "Carnot", "aceptó tu pedido y te confirma día y hora por acá.");
    notifier.notify(lead, "Carnot", "va en camino.");

    verify(whatsappService, times(1)).sendTemplateReturningId(any(), any(), any(), any(), any());
    assertThat(events(lead, LeadTimelineService.WHATSAPP_SENT)).hasSize(1);

    // Se envejece el evento a 11 minutos: la ventana pasó.
    em.flush();
    jdbcTemplate.update("update lead_events set created_at = dateadd('MINUTE', -11, current_timestamp) where lead_id = ?",
        lead.getId());
    em.clear();

    notifier.notify(lead, "Carnot", "va en camino.");

    verify(whatsappService, times(2)).sendTemplateReturningId(any(), any(), any(), any(), any());
  }

  @Test
  void elDedupeEsPorLead() {
    Lead a = webLead("098100007");
    Lead b = webLead("098100008");

    notifier.notify(a, "Carnot", "va en camino.");
    notifier.notify(b, "Carnot", "va en camino.");

    verify(whatsappService, times(2)).sendTemplateReturningId(any(), any(), any(), any(), any());
  }

  @Test
  void unaFallaDeEnvioNoRompe_yDejaEventoDeFalla_sinBloquearElReintento() {
    when(whatsappService.sendTemplateReturningId(anyString(), anyString(), anyString(), anyList(), any()))
        .thenReturn(null, WAMID);
    Lead lead = webLead("098100009");

    notifier.notify(lead, "Carnot", "va en camino.");

    assertThat(events(lead, LeadTimelineService.WHATSAPP_SENT)).isEmpty();
    List<LeadEvent> failed = events(lead, CustomerWhatsAppNotifier.TEMPLATE_FAILED_EVENT);
    assertThat(failed).hasSize(1);
    assertThat(failed.get(0).getActor()).isEqualTo("system");
    assertThat(failed.get(0).getMessage()).contains("customer_order_update").contains("59898100009");

    // Una falla no cuenta como "ya avisado": el próximo hook reintenta.
    notifier.notify(lead, "Carnot", "va en camino.");
    assertThat(events(lead, LeadTimelineService.WHATSAPP_SENT)).hasSize(1);
  }

  @Test
  void unaExcepcionDelServicioNoSePropaga() {
    when(whatsappService.sendTemplateReturningId(anyString(), anyString(), anyString(), anyList(), any()))
        .thenThrow(new IllegalStateException("boom"));
    Lead lead = webLead("098100010");

    notifier.notify(lead, "Carnot", "va en camino.");
    notifier.notify(null, "Carnot", "va en camino.");
  }

  @Test
  void sinBotonUrlConfigurado_noMandaSufijo() {
    CustomerWhatsAppNotifier sinBoton = new CustomerWhatsAppNotifier(
        whatsappService, timelineService, leadEventRepository, true, TEMPLATE, "es", false);
    Lead lead = webLead("098100011");

    sinBoton.notify(lead, "Carnot", "va en camino.");

    verify(whatsappService).sendTemplateReturningId(
        eq("59898100011"), eq(TEMPLATE), eq("es"), anyList(), eq((String) null));
  }

  @Test
  void laNovedadSeLimpiaYSeRecortaA200_yElNombreVacioTieneFallback() {
    Lead lead = webLead("098100012");
    String largo = "te respondió:\n«" + "palabra ".repeat(60) + "»\t fin";

    notifier.notify(lead, "  ", largo);

    List<String> params = verifySingleSend("59898100012", lead);
    assertThat(params.get(0)).isEqualTo("Tu proveedor");
    assertThat(params.get(1)).hasSizeLessThanOrEqualTo(200)
        .doesNotContain("\n").doesNotContain("\t").endsWith("…");
  }

  @Test
  void laFraseDeRespuestaCitaConComillasTipograficasYRecortaA140() {
    String reply = "x".repeat(300);
    String news = CustomerWhatsAppNotifier.providerReplyNews("¿para cuántas\npersonas es?");
    assertThat(news).isEqualTo("te respondió: «¿para cuántas personas es?»");

    String longNews = CustomerWhatsAppNotifier.providerReplyNews(reply);
    assertThat(longNews).startsWith("te respondió: «").endsWith("…»");
    assertThat(longNews.length()).isLessThanOrEqualTo(200);
  }

  // ------------------------------------------------------------ hooks

  @Test
  void hookAceptacion_deLaBandejaOWebhook() {
    Provider melissa = provider("Melissa Hook Aceptar");
    Lead lead = reserve(webLead("098100020"), melissa);

    leadAssignmentService.acceptForProvider(lead.getId(), melissa, "aceptó por WhatsApp: SÍ");

    assertThat(verifySingleSend("59898100020", lead))
        .containsExactly("Melissa Hook Aceptar", "aceptó tu pedido y te confirma día y hora por acá.");
  }

  @Test
  void hookAceptacion_conFranjaDiceCuandoVa() {
    Provider melissa = provider("Melissa Hook Franja");
    Lead lead = reserve(webLead("098100021"), melissa);

    leadAssignmentService.acceptForProvider(lead.getId(), melissa, "aceptó", "mañana de 14 a 16");

    assertThat(verifySingleSend("59898100021", lead))
        .containsExactly("Melissa Hook Franja", "aceptó tu pedido y va mañana de 14 a 16.");
  }

  @Test
  void hookAceptacion_porElPanel_updateLeadStatusAssigned() {
    Provider carnot = provider("Carnot Hook Panel");
    Lead lead = reserve(webLead("098100022"), carnot);

    providerSelfService.updateLeadStatus(carnot, lead.getId(), LeadStatus.ASSIGNED);

    assertThat(verifySingleSend("59898100022", lead))
        .containsExactly("Carnot Hook Panel", "aceptó tu pedido y te confirma día y hora por acá.");
  }

  @Test
  void hookMensajeDelProveedor_citaElTextoRecortado() {
    Provider carnot = provider("Carnot Hook Mensaje");
    Lead lead = reserve(webLead("098100023"), carnot);
    lead.setStatus(LeadStatus.ASSIGNED);
    leadRepository.save(lead);

    leadMessageService.postFromProvider(lead.getId(), "¿Para cuántas personas es la torta?");

    assertThat(verifySingleSend("59898100023", lead))
        .containsExactly("Carnot Hook Mensaje", "te respondió: «¿Para cuántas personas es la torta?»");
  }

  @Test
  void hookMensajeDelProveedor_leadWhatsappRecibeTextoLibreYNoPlantilla() {
    Provider carnot = provider("Carnot Hook Texto Libre");
    Lead lead = reserve(webLead("098100024"), carnot);
    lead.setChannel("whatsapp");
    leadRepository.save(lead);

    leadMessageService.postFromProvider(lead.getId(), "Voy a las 5");

    verify(whatsappService).sendText("098100024", "Carnot Hook Texto Libre: Voy a las 5");
    verify(whatsappService, never()).sendTemplateReturningId(any(), any(), any(), any(), any());
  }

  @Test
  void hookPropuestaDeHorario() {
    Provider carnot = provider("Carnot Hook Propuesta");
    Lead lead = reserve(webLead("098100025"), carnot);
    lead.setStatus(LeadStatus.ASSIGNED);
    leadRepository.save(lead);

    providerSelfService.proposeSchedule(carnot, lead.getId(), "mañana de 14 a 16");

    assertThat(verifySingleSend("59898100025", lead))
        .containsExactly("Carnot Hook Propuesta", "propone ir mañana de 14 a 16. Respondé SÍ o NO por acá.");
  }

  @Test
  void hookVoyEnCamino() {
    Provider carnot = provider("Carnot Hook Camino");
    Lead lead = reserve(webLead("098100026"), carnot);
    lead.setStatus(LeadStatus.ASSIGNED);
    leadRepository.save(lead);

    providerSelfService.notifyOnTheWay(carnot, lead.getId(), 20);

    assertThat(verifySingleSend("59898100026", lead))
        .containsExactly("Carnot Hook Camino", "va en camino.");
  }

  @Test
  void elAvisoDeCaminoNoSeDisfrazaDeRespuesta_unSoloEnvioPorHook() {
    Provider carnot = provider("Carnot Hook Sin Doble");
    Lead lead = reserve(webLead("098100027"), carnot);
    lead.setStatus(LeadStatus.ASSIGNED);
    leadRepository.save(lead);

    providerSelfService.notifyOnTheWay(carnot, lead.getId(), null);

    // postFromOps(sender=provider) del propio aviso NO dispara "te respondió".
    ArgumentCaptor<List<String>> unused = ArgumentCaptor.forClass(List.class);
    verify(whatsappService, times(1)).sendTemplateReturningId(any(), any(), any(), unused.capture(), any());
    assertThat(unused.getValue().get(1)).isEqualTo("va en camino.");
  }
}
