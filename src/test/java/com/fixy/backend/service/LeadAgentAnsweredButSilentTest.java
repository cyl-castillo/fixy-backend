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
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * BUG C de la guardia diaria del 2026-09-08, caso real lead #268:
 *
 * <pre>
 * fixy     Anotado: tu pedido en Solymar. ¿Me pasás la dirección exacta para coordinar?
 * cliente  Av. Giannattasio km 20, casa 3        (13:40:18)
 * fixy     — nada —                              (13:40:26, turno procesado)
 * </pre>
 *
 * El vecino contestó y quedó hablando solo. La cadena: el pedido ("que me
 * armen un ropero") no se clasificó, así que el lead tenía ZONA y URGENCIA
 * pero no CATEGORÍA; el builder del ack, con zona y urgencia ya sabidas, pedía
 * la dirección exacta — un dato que no existe en el lead y que
 * {@code computeBlockingFields} ni mira. Conteste lo que conteste el vecino,
 * el turno siguiente reconstruye la MISMA frase, y ahí el guard anti-loro
 * ({@code 420d96a}) la suprime. Bien suprimida: el bug es que atrás no había
 * nada. Silencio es peor que repetir.
 *
 * <p>El arreglo es el mismo criterio de siempre: sin categoría, lo único que
 * destraba el pedido es saber QUÉ necesita, así que eso se pregunta — y como
 * la pregunta termina en {@link LeadAgentService#ASK_WHAT_HAPPENED}, el guard
 * que ya existía la reconoce y, si también se repite, escala con el mensaje
 * honesto de qué cubre Fixy en vez de callarse.
 *
 * <p>Montaje idéntico al de LeadAgentUnknownServiceHonestyTest (y al de prod):
 * agente encendido, LLM sin credenciales —en prod los 4 turnos del día dieron
 * "sin respuesta utilizable"—, reengagement apagado. Sin {@code @Transactional}
 * porque {@code respondToCustomerAsync} corre {@code @Async} en otro hilo.
 */
@SpringBootTest
@TestPropertySource(properties = {
    "fixy.agent.enabled=true",
    "fixy.agent.provider=workersai",
    "fixy.cloudflare.account-id=",
    "fixy.cloudflare.api-token=",
    "fixy.reengagement.enabled=false"
})
class LeadAgentAnsweredButSilentTest {

  @Autowired private LeadAgentService leadAgentService;
  @Autowired private LeadMessageService leadMessageService;
  @Autowired private LeadMessageRepository messageRepository;
  @Autowired private LeadRepository leadRepository;

  @MockitoBean private TelegramNotifyService telegramNotifyService;

  /** El estado exacto del #268: zona y urgencia sabidas, categoría no. */
  private Lead leadWithZoneButNoCategory(String tokenSuffix) {
    Lead lead = new Lead();
    lead.setProblem("(pendiente)");
    lead.setChannel("web-chat");
    lead.setStatus(LeadStatus.NEW);
    lead.setLocation("Solymar");
    lead.setUrgency("baja");
    lead.setReadyForMatching(false);
    lead.setNotes("");
    lead.setAccessToken("tok-answered-silent-" + tokenSuffix + "-" + System.nanoTime());
    return leadRepository.save(lead);
  }

  /**
   * El caso del #268 de punta a punta: el vecino contesta la pregunta y TIENE
   * que recibir algo. La aserción que importa es la cantidad de mensajes de
   * Fixy: antes del arreglo el segundo turno no persistía ninguno.
   */
  @Test
  void caso268_elVecinoContestaYRecibeRespuesta_noSilencio() throws Exception {
    Lead lead = leadWithZoneButNoCategory("ropero");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token, "que me armen un ropero");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    String firstReply = lastAgentText(leadId);
    assertThat(firstReply).as("sin categoría no hay nada que coordinar: no se pide la dirección")
        .doesNotContain("dirección exacta");
    assertThat(firstReply).as("se pregunta lo único que destraba el pedido")
        .endsWith(LeadAgentService.ASK_WHAT_HAPPENED);
    assertThat(firstReply).as("y se reconoce lo que el vecino ya dijo")
        .startsWith("Anotado: tu pedido en Solymar.");

    int agentMessagesBefore = agentMessageCount(leadId);

    leadMessageService.postFromCustomer(leadId, token, "Av. Giannattasio km 20, casa 3");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    assertThat(agentMessageCount(leadId))
        .as("el vecino contestó: no puede quedar hablando solo (BUG C, lead #268)")
        .isGreaterThan(agentMessagesBefore);

    String secondReply = lastAgentText(leadId);
    assertThat(secondReply).as("no se le repite la misma frase")
        .isNotEqualTo(firstReply);
    assertThat(secondReply).as("se admite lo único cierto: no se entendió el pedido")
        .contains("no te terminé de entender");
    assertThat(secondReply).as("y se le dice qué SÍ consigue Fixy")
        .contains("Te cuento qué consigo hoy");
  }

  /**
   * Y el pedido no se pierde: las palabras del vecino quedan en el lead, que
   * es lo que hace visible la demanda de un oficio que Fixy todavía no tiene.
   */
  @Test
  void caso268_elPedidoQuedaEscritoConLasPalabrasDelVecino() throws Exception {
    Lead lead = leadWithZoneButNoCategory("ropero-problema");
    Long leadId = lead.getId();
    String token = lead.getAccessToken();

    leadMessageService.postFromCustomer(leadId, token, "que me armen un ropero");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);
    leadMessageService.postFromCustomer(leadId, token, "Av. Giannattasio km 20, casa 3");
    leadAgentService.respondToCustomerAsync(leadId);
    Thread.sleep(1500);

    Lead saved = leadRepository.findById(leadId).orElseThrow();
    assertThat(saved.getProblem()).as("deja de ser '(pendiente)' e invisible en el board")
        .contains("ropero");
  }

  /**
   * Con categoría conocida la pregunta de la dirección sigue teniendo sentido
   * (hay algo que coordinar): el arreglo no la saca del camino normal.
   */
  @Test
  void conCategoriaConocida_laDireccionExactaSeSiguePidiendo() throws Exception {
    Lead lead = leadWithZoneButNoCategory("con-categoria");
    lead.setDetectedCategory("aires_acondicionados");
    lead.setReadyForMatching(false);
    leadRepository.save(lead);

    String reply = leadAgentService.heuristicFallbackReply(
        leadRepository.findById(lead.getId()).orElseThrow());

    assertThat(reply).contains("dirección exacta");
  }

  /**
   * El guard de escalamiento comparaba por identidad contra la constante, así
   * que la variante con el ack de zona adelante —la del #268— se le escapaba.
   */
  @Test
  void elReconocimientoDeLaRepreguntaGenerica_valeConYSinAckDeZona() {
    assertThat(LeadAgentService.isAskWhatHappened(LeadAgentService.ASK_WHAT_HAPPENED)).isTrue();
    assertThat(LeadAgentService.isAskWhatHappened(
        "Anotado: tu pedido en Solymar. " + LeadAgentService.ASK_WHAT_HAPPENED)).isTrue();
    assertThat(LeadAgentService.isAskWhatHappened(
        "Anotado: tu pedido en Solymar. ¿Me pasás la dirección exacta para coordinar?")).isFalse();
    assertThat(LeadAgentService.isAskWhatHappened(null)).isFalse();
  }

  private int agentMessageCount(Long leadId) {
    return (int) messageRepository.findByLeadIdOrderByCreatedAtAsc(leadId).stream()
        .filter(m -> "fixy".equals(m.getSender()))
        .count();
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
