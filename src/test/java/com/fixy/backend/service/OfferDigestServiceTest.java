package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.PushSubscription;
import com.fixy.backend.repository.PushSubscriptionRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Digest semanal de ofertas por zona (Fase Push-1, FIXY_OFERTAS_PUSH_Y_MAPA.md
 * §3): {@code GET /api/offers/digest/preview} y {@code POST
 * /api/offers/digest/send}, con las reglas anti-spam duras.
 *
 * Zonas dedicadas y no usadas por otro test que aprueba ofertas o crea
 * suscripciones ({@code San José de Carrasco}, {@code Barra de Carrasco},
 * {@code Colinas de Solymar}) — evita el ruido de la H2 compartida entre
 * contextos (lección conocida del repo, ver PublicOfferSurfaceTest).
 * {@code @Transactional} para que nada de lo creado acá quede pisando el
 * resto de la suite.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OfferDigestServiceTest {

  private static final String ZONE_MAIN = "San José de Carrasco";
  private static final String ZONE_FEW_OFFERS = "Barra de Carrasco";
  private static final String ZONE_SEND = "Colinas de Solymar";

  @Autowired private MockMvc mockMvc;
  @Autowired private PushSubscriptionRepository pushSubscriptionRepository;

  private Integer createBusiness(String whatsapp) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Comercio Digest Test", "whatsappNumber": "%s", "category": "otro"}
                """.formatted(whatsapp)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
  }

  /** Crea y aprueba una oferta vigente (validUntil bien a futuro) en la zona dada. */
  private void createActiveOffer(Integer businessId, String zone, String title) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/offers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"businessId": %d, "title": "%s", "category": "otro", "zone": "%s", "validUntil": "2027-01-01T00:00:00Z"}
                """.formatted(businessId, title, zone)))
        .andExpect(status().isCreated())
        .andReturn();
    Integer offerId = JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(post("/api/offers/{id}/approve", offerId).with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());
  }

  private PushSubscription persistClientSub(Long leadId, String zone, OffsetDateTime lastOffersDigestAt) {
    PushSubscription sub = new PushSubscription();
    sub.setLeadId(leadId);
    sub.setEndpoint("https://digest-test.example/ep-" + leadId);
    sub.setP256dh("dummy-p256dh-digest-test");
    sub.setAuth("dummy-auth-digest-test");
    sub.setZone(zone);
    sub.setLastOffersDigestAt(lastOffersDigestAt);
    return pushSubscriptionRepository.save(sub);
  }

  @Test
  void preview_zonaConTresOfertasYDosSuscripcionesElegibles_willSendTrue() throws Exception {
    Integer businessId = createBusiness("098500001");
    createActiveOffer(businessId, ZONE_MAIN, "20% off en el local");
    createActiveOffer(businessId, ZONE_MAIN, "2x1 en el segundo servicio");
    createActiveOffer(businessId, ZONE_MAIN, "$500 de descuento fijo");

    persistClientSub(910001L, ZONE_MAIN, null); // nunca recibió digest: elegible
    persistClientSub(910002L, ZONE_MAIN, null); // idem
    persistClientSub(910003L, ZONE_MAIN, OffsetDateTime.now().minusDays(2)); // digest reciente: no elegible

    MvcResult res = mockMvc.perform(get("/api/offers/digest/preview").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> entry = zoneEntry(res, ZONE_MAIN);
    assertThat(entry.get("subscribers")).isEqualTo(3);
    assertThat(entry.get("activeOffers")).isEqualTo(3);
    assertThat(entry.get("willSend")).isEqualTo(true);
    assertThat((String) entry.get("reason")).isEqualTo("ok");
  }

  @Test
  void preview_zonaConMenosDeTresOfertas_willSendFalseConRazon() throws Exception {
    Integer businessId = createBusiness("098500002");
    createActiveOffer(businessId, ZONE_FEW_OFFERS, "Única oferta vigente");
    persistClientSub(910004L, ZONE_FEW_OFFERS, null);

    MvcResult res = mockMvc.perform(get("/api/offers/digest/preview").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> entry = zoneEntry(res, ZONE_FEW_OFFERS);
    assertThat(entry.get("subscribers")).isEqualTo(1);
    assertThat(entry.get("activeOffers")).isEqualTo(1);
    assertThat(entry.get("willSend")).isEqualTo(false);
    assertThat((String) entry.get("reason")).contains("menos de 3 ofertas vigentes");
  }

  @Test
  void preview_suscripcionSinZona_apareceAparteConWillSendFalse() throws Exception {
    persistClientSub(910005L, null, null);

    MvcResult res = mockMvc.perform(get("/api/offers/digest/preview").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    Map<String, Object> entry = zoneEntry(res, "sin zona");
    assertThat((Integer) entry.get("subscribers")).isGreaterThanOrEqualTo(1);
    assertThat(entry.get("willSend")).isEqualTo(false);
    assertThat((String) entry.get("reason")).contains("sin zona");
  }

  @Test
  void send_esIdempotenteDentroDeLosSieteDias() throws Exception {
    Integer businessId = createBusiness("098500003");
    createActiveOffer(businessId, ZONE_SEND, "20% off en el local");
    createActiveOffer(businessId, ZONE_SEND, "2x1 en el segundo servicio");
    createActiveOffer(businessId, ZONE_SEND, "$500 de descuento fijo");
    PushSubscription sub = persistClientSub(910006L, ZONE_SEND, null);

    mockMvc.perform(post("/api/offers/digest/send").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    PushSubscription afterFirstSend = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    OffsetDateTime firstDigestAt = afterFirstSend.getLastOffersDigestAt();
    assertThat(firstDigestAt).as("el primer envío marca last_offers_digest_at").isNotNull();

    // Segunda corrida inmediata (bien dentro de la ventana de 7 días): no debe reenviar.
    mockMvc.perform(post("/api/offers/digest/send").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk());

    PushSubscription afterSecondSend = pushSubscriptionRepository.findById(sub.getId()).orElseThrow();
    assertThat(afterSecondSend.getLastOffersDigestAt())
        .as("una segunda corrida dentro de los 7 días no debe reenviar ni pisar la marca")
        .isEqualTo(firstDigestAt);
  }

  @Test
  void send_sinZona_seCuentaComoSkippedNoZone() throws Exception {
    persistClientSub(910007L, null, null);

    MvcResult res = mockMvc.perform(post("/api/offers/digest/send").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();

    Integer skippedNoZone = JsonPath.read(res.getResponse().getContentAsString(), "$.skippedNoZone");
    assertThat(skippedNoZone).isGreaterThanOrEqualTo(1);
  }

  @SuppressWarnings("unchecked")
  private Map<String, Object> zoneEntry(MvcResult res, String zone) throws Exception {
    String body = res.getResponse().getContentAsString();
    List<Map<String, Object>> zones = JsonPath.read(body, "$.zones");
    return zones.stream()
        .filter(z -> zone.equals(z.get("zone")))
        .findFirst()
        .orElseThrow(() -> new AssertionError("no se encontró la zona '" + zone + "' en el preview: " + body));
  }
}
