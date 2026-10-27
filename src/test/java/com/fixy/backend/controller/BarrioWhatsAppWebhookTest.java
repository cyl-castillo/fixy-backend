package com.fixy.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.model.BarrioWaDraft;
import com.fixy.backend.model.Provider;
import com.fixy.backend.repository.BarrioReportRepository;
import com.fixy.backend.repository.BarrioWaDraftRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.BarrioTestImages;
import com.fixy.backend.service.WhatsAppService;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/**
 * Circuito Barrio del webhook de WhatsApp con payloads con la forma real de Meta
 * ("Webhooks: messages") para {@code image}, {@code location} e {@code interactive}. La descarga
 * de media (Graph API) y el envío están mockeados vía {@link WhatsAppService}. Incluye la
 * regresión: lo que ya hacía Fixy (aire, torta, pozo negro, proveedores del padrón) NO cambia.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.uploads.dir=target/barrio-test-uploads")
class BarrioWhatsAppWebhookTest {

  private static final AtomicInteger SEQ = new AtomicInteger(100);
  private static final String SECRET = "GPS-SECRETO-Pixel7";

  @Autowired private MockMvc mvc;
  @Autowired private BarrioWaDraftRepository drafts;
  @Autowired private BarrioReportRepository reports;
  @Autowired private LeadRepository leads;
  @Autowired private ProviderRepository providers;
  @MockitoBean private WhatsAppService whatsapp;

  @BeforeEach
  void resetMocks() throws Exception {
    reset(whatsapp);
    when(whatsapp.downloadMedia(anyString())).thenReturn(
        new WhatsAppService.MediaDownload(BarrioTestImages.withExif(BarrioTestImages.jpeg(800, 600), 1), "image/jpeg"));
    when(whatsapp.sendText(anyString(), anyString())).thenReturn(true);
    when(whatsapp.sendInteractiveList(anyString(), anyString(), anyString(), anyString(), anyList())).thenReturn(true);
  }

  /** Número único por test (la base es compartida dentro del contexto). */
  private static String newPhone() {
    return "59899" + String.format("%06d", SEQ.incrementAndGet());
  }

  // --------------------------------------------------------- payloads de Meta

  private static String envelope(String phone, String messageJson) {
    return """
        {"object":"whatsapp_business_account","entry":[{"id":"1234567890","changes":[{"value":{
          "messaging_product":"whatsapp",
          "metadata":{"display_phone_number":"59896251101","phone_number_id":"109876543210"},
          "contacts":[{"profile":{"name":"Vecina de Lagomar"},"wa_id":"%s"}],
          "messages":[%s]},"field":"messages"}]}]}
        """.formatted(phone, messageJson);
  }

  private void send(String phone, String messageJson) throws Exception {
    mvc.perform(post("/api/webhooks/whatsapp").contentType(MediaType.APPLICATION_JSON).content(envelope(phone, messageJson)))
        .andExpect(status().isOk());
  }

  private void text(String phone, String body) throws Exception {
    send(phone, """
        {"from":"%s","id":"wamid.HBgLNTk4OTkxMTEyMjIVAgASGBQ3QTVCMTNFNzY5QTA1","timestamp":"1761600000",
         "text":{"body":"%s"},"type":"text"}""".formatted(phone, body));
  }

  private void image(String phone, String mediaId) throws Exception {
    send(phone, """
        {"from":"%s","id":"wamid.HBgLNTk4OTkxMTEyMjIVAgASGBQ3QTVCMTNFNzY5QTA2","timestamp":"1761600010",
         "type":"image","image":{"mime_type":"image/jpeg","sha256":"Zm9vYmFyYmF6cXV4","id":"%s"}}""".formatted(phone, mediaId));
  }

  private void location(String phone, double lat, double lng) throws Exception {
    send(phone, """
        {"from":"%s","id":"wamid.HBgLNTk4OTkxMTEyMjIVAgASGBQ3QTVCMTNFNzY5QTA3","timestamp":"1761600020",
         "type":"location","location":{"latitude":%s,"longitude":%s,"name":"Av. Giannattasio","address":"km 24"}}"""
        .formatted(phone, lat, lng));
  }

  private void listReply(String phone, String id, String title) throws Exception {
    send(phone, """
        {"context":{"from":"59896251101","id":"wamid.HBgLNTk4OTYyNTExMDEVAgARGBI5"},
         "from":"%s","id":"wamid.HBgLNTk4OTkxMTEyMjIVAgASGBQ3QTVCMTNFNzY5QTA4","timestamp":"1761600030",
         "type":"interactive","interactive":{"type":"list_reply","list_reply":{"id":"%s","title":"%s","description":"x"}}}"""
        .formatted(phone, id, title));
  }

  private List<String> sentTexts() {
    ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
    verify(whatsapp, atLeastOnce()).sendText(anyString(), cap.capture());
    return cap.getAllValues();
  }

  private BarrioWaDraft draftOf(String phone) {
    return drafts.findByPhone(phone).orElse(null);
  }

  private List<BarrioReport> reportsOf(String phone) {
    return reports.findAll().stream().filter(r -> phone.equals(r.getReporterPhone())).toList();
  }

  // ------------------------------------------------------------------ Barrio

  @Test
  void textoFotoYUbicacionCreanElReclamoPendienteYResponden() throws Exception {
    String phone = newPhone();

    text(phone, "Hay un bache enorme en la calle, rompe los autos");
    assertThat(draftOf(phone).getCategory()).isEqualTo("bache");
    verify(whatsapp).sendText(eq(phone), contains("Anotado: bache"));

    image(phone, "1234567890123456");
    verify(whatsapp).downloadMedia("1234567890123456");
    assertThat(draftOf(phone).getPhotoPath()).startsWith("barrio/").endsWith(".jpg");
    verify(whatsapp).sendText(eq(phone), contains("Falta la ubicación"));

    location(phone, -34.8270, -55.9330);

    assertThat(draftOf(phone)).isNull();
    List<BarrioReport> created = reportsOf(phone);
    assertThat(created).hasSize(1);
    BarrioReport r = created.get(0);
    assertThat(r.getStatus()).isEqualTo(BarrioReport.PENDING_REVIEW);
    assertThat(r.getChannel()).isEqualTo("whatsapp");
    assertThat(r.getCategory()).isEqualTo("bache");
    assertThat(r.getZone()).isEqualTo("Lagomar");
    assertThat(r.getReporterPhone()).isEqualTo(phone);
    verify(whatsapp).sendText(eq(phone),
        contains("Recibido, reclamo #" + r.getId() + ". Lo revisamos y en unas horas aparece en el mapa: https://www.fixy.com.uy/barrio/r/" + r.getId()));

    // La foto guardada no conserva el EXIF de lo que mandó el celular.
    byte[] saved = Files.readAllBytes(Path.of("target/barrio-test-uploads").resolve(r.getPhotoPath()));
    assertThat(new String(saved, StandardCharsets.ISO_8859_1)).doesNotContain(SECRET).doesNotContain("Exif");
  }

  @Test
  void fotoPrimeroPideCategoriaConLaListaYElToqueLaCompleta() throws Exception {
    String phone = newPhone();

    image(phone, "media-A");
    ArgumentCaptor<List<WhatsAppService.ListRow>> rows = ArgumentCaptor.forClass(List.class);
    verify(whatsapp).sendInteractiveList(eq(phone), anyString(), anyString(), anyString(), rows.capture());
    assertThat(rows.getValue()).extracting(WhatsAppService.ListRow::id)
        .containsExactly("barrio_bache", "barrio_poda", "barrio_luminaria", "barrio_otro");
    assertThat(rows.getValue()).allSatisfy(row -> assertThat(row.title().length()).isLessThanOrEqualTo(24));
    assertThat(draftOf(phone).getCategory()).isNull();

    listReply(phone, "barrio_luminaria", "Luminaria apagada");
    assertThat(draftOf(phone).getCategory()).isEqualTo("luminaria");
    verify(whatsapp).sendText(eq(phone), contains("Falta la ubicación"));

    location(phone, -34.8050, -56.0150);
    BarrioReport r = reportsOf(phone).get(0);
    assertThat(r.getCategory()).isEqualTo("luminaria");
    assertThat(r.getZone()).isEqualTo("Lomas de Carrasco");
  }

  @Test
  void ubicacionFueraDeLaCajaAvisaQueSoloCiudadDeLaCostaYNoCreaNada() throws Exception {
    String phone = newPhone();
    text(phone, "hay una luminaria apagada");
    location(phone, -34.9033, -56.1882); // Montevideo

    verify(whatsapp).sendText(eq(phone), contains("solo cubre Ciudad de la Costa"));
    assertThat(reportsOf(phone)).isEmpty();
    assertThat(draftOf(phone).hasLocation()).isFalse();
  }

  @Test
  void otraCosaDescartaElBorradorYSigueElFlujoNormalDeFixy() throws Exception {
    String phone = newPhone();
    image(phone, "media-B");
    String photo = draftOf(phone).getPhotoPath();
    assertThat(Path.of("target/barrio-test-uploads").resolve(photo)).exists();

    listReply(phone, "barrio_otro", "Otra cosa");

    assertThat(draftOf(phone)).isNull();
    assertThat(Path.of("target/barrio-test-uploads").resolve(photo)).doesNotExist();
    assertThat(reportsOf(phone)).isEmpty();
    // Fixy siguió el camino de siempre: abrió su conversación con ese número.
    assertThat(leads.findByPhoneAndChannelOrderByCreatedAtDesc(phone, "whatsapp")).isNotEmpty();
  }

  @Test
  void cancelarDescartaElBorrador() throws Exception {
    String phone = newPhone();
    text(phone, "bache en mi calle");
    assertThat(draftOf(phone)).isNotNull();
    text(phone, "Cancelar");
    assertThat(draftOf(phone)).isNull();
    verify(whatsapp).sendText(eq(phone), contains("descarté el reclamo"));
  }

  @Test
  void unBorradorVencidoA2HorasNoSeReusa() throws Exception {
    String phone = newPhone();
    text(phone, "hay un bache");
    BarrioWaDraft d = draftOf(phone);
    d.setUpdatedAt(OffsetDateTime.now().minusHours(3));
    drafts.save(d);

    location(phone, -34.8270, -55.9330);

    BarrioWaDraft fresh = draftOf(phone);
    assertThat(fresh.getCategory()).isNull();      // el viejo "bache" venció
    assertThat(fresh.hasLocation()).isTrue();
    assertThat(reportsOf(phone)).isEmpty();
  }

  @Test
  void siLaDescargaDeLaFotoFallaPideReenviarYNoGuardaNada() throws Exception {
    String phone = newPhone();
    when(whatsapp.downloadMedia("rota")).thenReturn(null);
    text(phone, "bache");
    image(phone, "rota");
    verify(whatsapp).sendText(eq(phone), contains("No pude abrir la foto"));
    assertThat(draftOf(phone).hasPhoto()).isFalse();
  }

  // -------------------------------------------------------------- regresión

  @Test
  void elPedidoNormalDeAireNoEntraAlCircuitoBarrio() throws Exception {
    String phone = newPhone();
    text(phone, "Se me rompió el aire acondicionado, no enfría");

    assertThat(draftOf(phone)).isNull();
    assertThat(reportsOf(phone)).isEmpty();
    verify(whatsapp, never()).downloadMedia(anyString());
    verify(whatsapp, never()).sendInteractiveList(anyString(), contains("mapa del barrio"), anyString(), anyString(), anyList());
    assertThat(leads.findByPhoneAndChannelOrderByCreatedAtDesc(phone, "whatsapp")).hasSize(1);
  }

  @Test
  void pozoNegroYBarometricaSiguenSiendoServiciosDeFixy() throws Exception {
    String phone = newPhone();
    text(phone, "Tengo el pozo negro lleno, necesito la barométrica");

    assertThat(draftOf(phone)).isNull();
    assertThat(reportsOf(phone)).isEmpty();
    assertThat(leads.findByPhoneAndChannelOrderByCreatedAtDesc(phone, "whatsapp")).hasSize(1);
  }

  @Test
  void conUnPedidoDeFixyAbiertoLasFotosYLasPalabrasAmbiguasSiguenIgnoradasOEnrutadasComoAntes() throws Exception {
    String phone = newPhone();
    text(phone, "Necesito un plomero, se me rompió una canilla");
    int leadsBefore = leads.findByPhoneAndChannelOrderByCreatedAtDesc(phone, "whatsapp").size();
    assertThat(leadsBefore).isEqualTo(1);

    image(phone, "media-C");                        // antes: "sin texto, ignoramos"
    location(phone, -34.8270, -55.9330);            // idem
    text(phone, "también tengo ramas de poda en el fondo"); // ambigua: jardinería, la sigue el agente

    verify(whatsapp, never()).downloadMedia(anyString());
    assertThat(draftOf(phone)).isNull();
    assertThat(reportsOf(phone)).isEmpty();
    assertThat(leads.findByPhoneAndChannelOrderByCreatedAtDesc(phone, "whatsapp")).hasSize(leadsBefore);
  }

  @Test
  void unProveedorDelPadronNuncaEntraAlCircuitoBarrio() throws Exception {
    String phone = newPhone();
    Provider provider = providers.findAll().get(0);
    String originalWhatsapp = provider.getWhatsappNumber();
    provider.setWhatsappNumber(phone);
    providers.save(provider);
    try {
      text(phone, "hay un bache enorme en la calle");
      image(phone, "media-D");
      location(phone, -34.8270, -55.9330);

      verify(whatsapp, never()).downloadMedia(anyString());
      assertThat(draftOf(phone)).isNull();
      assertThat(reportsOf(phone)).isEmpty();
    } finally {
      provider.setWhatsappNumber(originalWhatsapp);
      providers.save(provider);
    }
  }
}
