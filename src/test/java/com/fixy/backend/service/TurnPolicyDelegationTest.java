package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadMessage;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.UserLeadRepository;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2, pieza 4 (arrastre de Fase 1): el loop de turnos y el matching hablan SOLO con
 * {@link TurnPolicy}. Las guardas puras de la interfaz devuelven lo mismo que los estáticos de
 * {@link HomeServicesPolicy} (que siguen existiendo para los delegadores de test de
 * {@link LeadAgentService}), y los helpers que estaban duplicados ahora viven una sola vez.
 */
class TurnPolicyDelegationTest {

  private final LeadMessageService leadMessageService = mock(LeadMessageService.class);
  private final HomeServicesTurnPolicy policy =
      new HomeServicesTurnPolicy(leadMessageService, mock(AgentService.class));

  private static Lead lead(String category, String location, String phone) {
    Lead lead = new Lead();
    lead.setId(7L);
    lead.setDetectedCategory(category);
    lead.setLocation(location);
    lead.setPhone(phone);
    return lead;
  }

  private static LeadMessage message(String sender, String text) {
    LeadMessage m = new LeadMessage();
    m.setSender(sender);
    m.setText(text);
    return m;
  }

  @Test
  void lasGuardasPurasDeLaInterfazDevuelvenLoMismoQueLosEstaticos() {
    for (String text : new String[] {"cuánto sale?", "ok", "gracias", "me equivoqué, es aires", "hola",
        "mi número es 099123456", "¿es de confianza?", "en qué zona estás?", "dejame tu WhatsApp", "[smoke] hola", null, ""}) {
      assertThat(policy.isPriceQuestion(text)).isEqualTo(HomeServicesPolicy.isPriceQuestion(text));
      assertThat(policy.isAcknowledgment(text)).isEqualTo(HomeServicesPolicy.isAcknowledgment(text));
      assertThat(policy.isExplicitCorrection(text)).isEqualTo(HomeServicesPolicy.isExplicitCorrection(text));
      assertThat(policy.isTrustQuestion(text)).isEqualTo(HomeServicesPolicy.isTrustQuestion(text));
      assertThat(policy.phoneMentionedIn(text)).isEqualTo(HomeServicesPolicy.phoneMentionedIn(text));
      assertThat(policy.asksForZone(text)).isEqualTo(HomeServicesPolicy.asksForZone(text));
      assertThat(policy.asksForContactPhone(text)).isEqualTo(HomeServicesPolicy.asksForContactPhone(text));
      assertThat(policy.claimsStillSearching(text)).isEqualTo(HomeServicesPolicy.claimsStillSearching(text));
      assertThat(policy.isAskWhatHappened(text)).isEqualTo(HomeServicesPolicy.isAskWhatHappened(text));
    }
    assertThat(policy.detectCategoryFromMessages(List.of("necesito un plomero", "hola")))
        .isEqualTo(HomeServicesPolicy.detectCategoryFromMessages(List.of("necesito un plomero", "hola")))
        .isEqualTo("plomeria");
    assertThat(policy.shouldForceZoneQuestion(true, null, "hola", "contame más", false))
        .isEqualTo(HomeServicesPolicy.shouldForceZoneQuestion(true, null, "hola", "contame más", false))
        .isTrue();
    assertThat(policy.shouldAskContactPhone(true, true, null, false, "listo"))
        .isEqualTo(HomeServicesPolicy.shouldAskContactPhone(true, true, null, false, "listo"))
        .isTrue();
    Lead withProvider = lead("plomeria", "Solymar", null);
    withProvider.setAssignedProviderId(3L);
    withProvider.setStatus(LeadStatus.ASSIGNED);
    assertThat(policy.hasProviderOnTheLine(withProvider)).isTrue()
        .isEqualTo(HomeServicesPolicy.hasProviderOnTheLine(withProvider));
    Lead smoke = lead("plomeria", "Solymar", null);
    smoke.setProblem("[smoke] prueba");
    assertThat(policy.isSmokeLead(smoke)).isTrue().isEqualTo(HomeServicesPolicy.isSmokeLead(smoke));
    assertThat(policy.priceReply(lead("plomeria", "Solymar", null)))
        .isEqualTo(HomeServicesPolicy.priceReply(lead("plomeria", "Solymar", null)));
    assertThat(policy.whatFixyCoversReply()).isEqualTo(HomeServicesPolicy.whatFixyCoversReply());
    assertThat(policy.askWhatHappenedReply()).isSameAs(HomeServicesPolicy.ASK_WHAT_HAPPENED);
    assertThat(policy.correctionPhrases()).isSameAs(HomeServicesPolicy.correctionPhrases());
  }

  @Test
  void elPedidoDeWhatsAppSeAnexaUnaSolaVezYSoloSiFalta() {
    when(leadMessageService.recentForAgent(eq(7L), anyInt())).thenReturn(List.of());
    assertThat(policy.withContactPhoneAsk(lead("plomeria", "Solymar", null), "Ya te busco uno."))
        .isEqualTo("Ya te busco uno. " + HomeServicesPolicy.CONTACT_PHONE_ASK);
    // Ya tiene teléfono: no se pide.
    assertThat(policy.withContactPhoneAsk(lead("plomeria", "Solymar", "099123456"), "Ya te busco uno."))
        .isEqualTo("Ya te busco uno.");
    // El mensaje ya lo pide: no se duplica.
    assertThat(policy.withContactPhoneAsk(lead("plomeria", "Solymar", null), "¿Me pasás tu WhatsApp?"))
        .isEqualTo("¿Me pasás tu WhatsApp?");
    // Y el re-export del loop es la misma constante.
    assertThat(LeadAgentService.CONTACT_PHONE_ASK).isEqualTo(HomeServicesPolicy.CONTACT_PHONE_ASK);
  }

  @Test
  void contactPhoneAlreadyAskedMiraLosMensajesDelAgenteYNoInsisteAnteLaDuda() {
    when(leadMessageService.recentForAgent(eq(7L), anyInt())).thenReturn(List.of(
        message("customer", "hola, mi whatsapp es otro"),
        message("fixy", "Contame qué necesitás.")));
    assertThat(policy.contactPhoneAlreadyAsked(7L)).isFalse();

    when(leadMessageService.recentForAgent(eq(7L), anyInt())).thenReturn(List.of(
        message("fixy", HomeServicesPolicy.CONTACT_PHONE_ASK)));
    assertThat(policy.contactPhoneAlreadyAsked(7L)).isTrue();

    when(leadMessageService.recentForAgent(eq(7L), anyInt())).thenThrow(new RuntimeException("db caída"));
    assertThat(policy.contactPhoneAlreadyAsked(7L)).as("ante la duda no repreguntar").isTrue();
  }

  @Test
  void losHelpersUnificados() {
    assertThat(policy.humanCategory("pasteleria")).isEqualTo("pastelería");
    assertThat(policy.humanCategory("categoria-inventada")).isEqualTo("categoria-inventada");
    assertThat(policy.humanCategory(null)).isEqualTo("tu pedido");
    assertThat(policy.safe(null, "x")).isEqualTo("x");
    assertThat(policy.safe("  ", "x")).isEqualTo("x");
    assertThat(policy.safe("valor", "x")).isEqualTo("valor");
    assertThat(policy.isMvpCategory("mandados")).isTrue();
    assertThat(policy.isMvpCategory("electricidad")).isFalse();
    assertThat(policy.isMvpCategory("otro")).isFalse();
  }

  /** Prueba de uso: buildContext del loop pide la etiqueta de la categoría a la política inyectada. */
  @Test
  void elLoopConsultaALaPoliticaInyectada() {
    TurnPolicy injected = mock(TurnPolicy.class);
    when(injected.safe(any(), any())).thenAnswer(inv -> {
      String value = inv.getArgument(0);
      return value == null || value.isBlank() ? inv.getArgument(1) : value;
    });
    when(injected.humanCategory(any())).thenReturn("CATEGORIA-DE-LA-POLITICA");
    LeadAgentService service = new LeadAgentService(new ObjectMapper(), mock(LeadMessageService.class),
        mock(LeadRepository.class), mock(UserLeadRepository.class), mock(ProviderCatalogService.class),
        mock(WhatsAppMenuService.class), mock(LeadTimelineService.class), mock(AgentService.class),
        mock(TelegramNotifyService.class), mock(SearchDeadlineService.class), mock(LlmGateway.class),
        injected, mock(LeadMatchingService.class));

    String context = service.buildContext(lead("plomeria", "Solymar", null));

    assertThat(context).contains("Servicio: CATEGORIA-DE-LA-POLITICA");
    verify(injected, atLeastOnce()).humanCategory("plomeria");
    verify(injected, atLeastOnce()).isMvpCategory("plomeria");
  }

  /**
   * Guarda de arquitectura: LeadMatchingService no nombra a HomeServicesPolicy, y en LeadAgentService
   * las únicas referencias son los delegadores estáticos que exigen los tests ({@code return
   * HomeServicesPolicy.x(...)}), las re-exportaciones de constantes y comentarios — ninguna llamada del loop.
   */
  @Test
  void elLoopYElMatchingNoLlamanALosEstaticosDeHomeServicesPolicy() throws IOException {
    Path dir = Path.of("src/main/java/com/fixy/backend/service");
    List<String> matching = Files.readAllLines(dir.resolve("LeadMatchingService.java"));
    assertThat(matching).noneMatch(line -> line.contains("HomeServicesPolicy"));

    List<String> loop = Files.readAllLines(dir.resolve("LeadAgentService.java"));
    List<String> offenders = loop.stream()
        .filter(line -> line.contains("HomeServicesPolicy."))
        .map(String::strip)
        .filter(line -> !line.startsWith("*") && !line.startsWith("//") && !line.startsWith("/*"))
        .filter(line -> !line.startsWith("return HomeServicesPolicy.")
            && !line.matches("static final String \\w+ = HomeServicesPolicy\\.\\w+;"))
        .toList();
    assertThat(offenders).isEmpty();
  }
}
