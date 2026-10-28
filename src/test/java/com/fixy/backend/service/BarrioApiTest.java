package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.BarrioReport;
import com.fixy.backend.repository.BarrioReportRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder;

/**
 * Endpoints públicos y de ops de Fixy Barrio (B1) contra el contexto completo. Cada test usa su
 * propia latitud (≥ 1 km entre sí) para que el dedupe de un test no toque a otro: la base H2 es
 * compartida dentro del contexto. MockMvc no cubre CORS ni lo que Jackson descarta: eso se
 * verifica aparte con curl contra el servidor real.
 */
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(properties = "fixy.uploads.dir=target/barrio-test-uploads")
class BarrioApiTest {

  private static final String SECRET_DESC = "El pozo está frente al almacén de don Ramón, cuidado";
  private static final String PHONE_INPUT = "099 429 328";
  private static final String PHONE_NORMALIZED = "59899429328";

  @Autowired private MockMvc mvc;
  @Autowired private BarrioReportRepository reports;

  // ------------------------------------------------------------------ helpers

  private static MockMultipartHttpServletRequestBuilder createRequest(
      String category, String lat, String lng, boolean withPhoto, String description, String phone
  ) throws Exception {
    MockMultipartHttpServletRequestBuilder b = multipart("/api/public/barrio/reports");
    if (category != null) b.param("category", category);
    if (lat != null) b.param("lat", lat);
    if (lng != null) b.param("lng", lng);
    if (description != null) b.param("description", description);
    if (phone != null) b.param("phone", phone);
    if (withPhoto) {
      b.file(new MockMultipartFile("photo", "foto.jpg", "image/jpeg",
          BarrioTestImages.withExif(BarrioTestImages.jpeg(640, 480), 1)));
    }
    return b;
  }

  private long create(String category, double lat, double lng) throws Exception {
    MvcResult res = mvc.perform(createRequest(category, String.valueOf(lat), String.valueOf(lng), true, SECRET_DESC, PHONE_INPUT))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"))
        .andReturn();
    return ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.id")).longValue();
  }

  private MvcResult ops(MockHttpServletRequestBuilder b) throws Exception {
    return mvc.perform(b.with(httpBasic("test-ops", "test-pass"))).andReturn();
  }

  private String approve(long id) throws Exception {
    MvcResult r = ops(post("/api/ops/barrio/reports/{id}/approve", id));
    assertThat(r.getResponse().getStatus()).isEqualTo(200);
    return r.getResponse().getContentAsString();
  }

  private long closure(long reportId) throws Exception {
    mvc.perform(multipart("/api/public/barrio/reports/{id}/closure", reportId)
            .file(new MockMultipartFile("photo", "arreglado.jpg", "image/jpeg", BarrioTestImages.jpeg(320, 240)))
            .param("comment", "Ya lo taparon, gracias"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("PENDING_REVIEW"));
    String pending = ops(get("/api/ops/barrio/pending")).getResponse().getContentAsString();
    java.util.List<Number> ids = JsonPath.read(pending, "$.closures[?(@.reportId==" + reportId + ")].id");
    return ids.get(ids.size() - 1).longValue();
  }

  // -------------------------------------------------------------------- alta

  @Test
  void altaWebQuedaPendienteYNoEsPublicaHastaQueOpsApruebe() throws Exception {
    long id = create("bache", -34.8270123456, -55.9330987654);

    mvc.perform(get("/api/public/barrio/reports/{id}", id)).andExpect(status().isNotFound());
    String list = mvc.perform(get("/api/public/barrio/reports?status=all")).andReturn().getResponse().getContentAsString();
    assertThat((java.util.List<Number>) JsonPath.read(list, "$[?(@.id==" + id + ")].id")).isEmpty();

    mvc.perform(get("/api/ops/barrio/pending")).andExpect(status().isUnauthorized());
    mvc.perform(get("/api/ops/barrio/pending").with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.reports[?(@.id==" + id + ")].description").value(SECRET_DESC))
        .andExpect(jsonPath("$.reports[?(@.id==" + id + ")].reporterPhone").value("********328"))
        .andExpect(jsonPath("$.reports[?(@.id==" + id + ")].channel").value("web"))
        .andExpect(jsonPath("$.reports[?(@.id==" + id + ")].zone").value("Lagomar"));

    approve(id);

    MvcResult detail = mvc.perform(get("/api/public/barrio/reports/{id}", id))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(id))
        .andExpect(jsonPath("$.category").value("bache"))
        .andExpect(jsonPath("$.categoryLabel").value("Bache"))
        .andExpect(jsonPath("$.zone").value("Lagomar"))
        .andExpect(jsonPath("$.lat").value(-34.8270))
        .andExpect(jsonPath("$.lng").value(-55.9331))
        .andExpect(jsonPath("$.status").value("PUBLISHED"))
        .andExpect(jsonPath("$.supporters").value(1))
        .andExpect(jsonPath("$.daysOpen").value(0))
        .andExpect(jsonPath("$.photoUrl").value(containsString("/uploads/barrio/")))
        .andReturn();

    // Nunca teléfono ni descripción en lo público (ni como clave ni como valor).
    String publicDetail = detail.getResponse().getContentAsString();
    String publicList = mvc.perform(get("/api/public/barrio/reports")).andReturn().getResponse().getContentAsString();
    for (String body : new String[] {publicDetail, publicList}) {
      assertThat(body).doesNotContain("description").doesNotContain("reporterPhone").doesNotContain("phone")
          .doesNotContain("channel").doesNotContain("rejectReason")
          .doesNotContain("Ramón").doesNotContain("429").doesNotContain(PHONE_NORMALIZED);
    }
    assertThat((java.util.List<Number>) JsonPath.read(publicList, "$[?(@.id==" + id + ")].id")).hasSize(1);
  }

  @Test
  void validaciones() throws Exception {
    mvc.perform(createRequest("bache", "-34.80", "-55.95", false, null, null))
        .andExpect(status().isBadRequest());                       // sin foto
    mvc.perform(createRequest("otra", "-34.80", "-55.95", true, null, null))
        .andExpect(status().isBadRequest());                       // categoría
    mvc.perform(createRequest("bache", null, "-55.95", true, null, null))
        .andExpect(status().isBadRequest());                       // sin lat
    mvc.perform(createRequest("bache", "-34.80", "-55.95", true, "x".repeat(501), null))
        .andExpect(status().isBadRequest());                       // descripción larga
    mvc.perform(createRequest("bache", "-34.80", "-55.95", true, null, "12"))
        .andExpect(status().isBadRequest());                       // teléfono inválido
    mvc.perform(multipart("/api/public/barrio/reports")
            .file(new MockMultipartFile("photo", "x.pdf", "application/pdf", new byte[] {1, 2, 3}))
            .param("category", "bache").param("lat", "-34.80").param("lng", "-55.95"))
        .andExpect(status().isBadRequest());                       // no es imagen
    mvc.perform(get("/api/public/barrio/reports?status=cualquiera")).andExpect(status().isBadRequest());
  }

  @Test
  void fueraDeLaCajaDeCiudadDeLaCostaEs422FueraDeZona() throws Exception {
    mvc.perform(createRequest("bache", "-34.9033", "-56.1882", true, null, null))   // Montevideo
        .andExpect(status().isUnprocessableEntity())
        .andExpect(jsonPath("$.error").value("fuera_de_zona"));
    long before = reports.count();
    mvc.perform(createRequest("poda", "-34.60", "-55.95", true, null, null)).andExpect(status().isUnprocessableEntity());
    assertThat(reports.count()).isEqualTo(before); // y no deja nada guardado
  }

  // ------------------------------------------------------------------ dedupe

  @Test
  void dedupeAlAprobarUnSegundoReporteA20MetrosSeFundeEnElPrimero() throws Exception {
    double lat = -34.8105;
    long a = create("bache", lat, -55.9500);
    approve(a);

    // ~18 m al oeste, misma categoría
    MvcResult res = mvc.perform(createRequest("bache", String.valueOf(lat), "-55.9502", true, null, null))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.possibleDuplicateOf").value(a))
        .andReturn();
    long b = ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.id")).longValue();

    String approved = approve(b);
    assertThat((Integer) JsonPath.read(approved, "$.mergedIntoId")).isEqualTo((int) a);
    assertThat((String) JsonPath.read(approved, "$.status")).isEqualTo("MERGED");

    // El destino suma un apoyo; pedir B devuelve A; B no aparece en el listado.
    mvc.perform(get("/api/public/barrio/reports/{id}", a)).andExpect(jsonPath("$.supporters").value(2));
    mvc.perform(get("/api/public/barrio/reports/{id}", b))
        .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(a));
    String list = mvc.perform(get("/api/public/barrio/reports")).andReturn().getResponse().getContentAsString();
    assertThat((java.util.List<Number>) JsonPath.read(list, "$[?(@.id==" + b + ")].id")).isEmpty();
    // Aprobar de nuevo un MERGED es 409.
    assertThat(ops(post("/api/ops/barrio/reports/{id}/approve", b)).getResponse().getStatus()).isEqualTo(409);
  }

  @Test
  void dedupeNoFundeSiLaCategoriaEsOtraNiSiEstaA60Metros() throws Exception {
    double lat = -34.8205;
    long a = create("bache", lat, -55.9600);
    approve(a);
    long otraCategoria = create("luminaria", lat, -55.9602);
    long lejos = create("bache", lat, -55.9607);   // ~64 m
    assertThat((String) JsonPath.read(approve(otraCategoria), "$.status")).isEqualTo("PUBLISHED");
    assertThat((String) JsonPath.read(approve(lejos), "$.status")).isEqualTo("PUBLISHED");
  }

  @Test
  void losDiasAbiertosCuentanDesdeElReporteMasViejo() throws Exception {
    double lat = -34.8305;
    long viejo = create("poda", lat, -55.9700);
    long nuevo = create("poda", lat, -55.9701);
    BarrioReport r = reports.findById(viejo).orElseThrow();
    r.setCreatedAt(OffsetDateTime.now().minusDays(10));
    reports.save(r);

    approve(nuevo);                       // el más nuevo se publica primero
    approve(viejo);                       // el viejo se funde en el nuevo...
    mvc.perform(get("/api/public/barrio/reports/{id}", nuevo))
        .andExpect(jsonPath("$.supporters").value(2))
        .andExpect(jsonPath("$.daysOpen").value(10));   // ...y los días cuentan desde el viejo
  }

  // ---------------------------------------------------------------- moderación

  @Test
  void rechazarConMotivoYNoSePuedeAprobarDespues() throws Exception {
    long id = create("luminaria", -34.7905, -55.9000);
    assertThat(ops(post("/api/ops/barrio/reports/{id}/reject", id)
        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"No es en la vía pública\"}"))
        .getResponse().getStatus()).isEqualTo(200);
    assertThat(reports.findById(id).orElseThrow().getStatus()).isEqualTo(BarrioReport.REJECTED);
    assertThat(reports.findById(id).orElseThrow().getRejectReason()).isEqualTo("No es en la vía pública");
    mvc.perform(get("/api/public/barrio/reports/{id}", id)).andExpect(status().isNotFound());
    assertThat(ops(post("/api/ops/barrio/reports/{id}/approve", id)).getResponse().getStatus()).isEqualTo(409);
    String pending = ops(get("/api/ops/barrio/pending")).getResponse().getContentAsString();
    assertThat((java.util.List<Number>) JsonPath.read(pending, "$.reports[?(@.id==" + id + ")].id")).isEmpty();
  }

  @Test
  void patchCambiaCategoriaYRecalculaElBarrio() throws Exception {
    long id = create("bache", -34.8270, -55.9330);       // Lagomar
    MvcResult res = ops(patch("/api/ops/barrio/reports/{id}", id)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"category\":\"poda\",\"lat\":-34.7900,\"lng\":-55.9000}"));
    assertThat(res.getResponse().getStatus()).isEqualTo(200);
    String body = res.getResponse().getContentAsString();
    assertThat((String) JsonPath.read(body, "$.category")).isEqualTo("poda");
    assertThat((String) JsonPath.read(body, "$.zone")).isEqualTo("El Pinar");

    MvcResult out = ops(patch("/api/ops/barrio/reports/{id}", id)
        .contentType(MediaType.APPLICATION_JSON).content("{\"lat\":-34.60,\"lng\":-55.9}"));
    assertThat(out.getResponse().getStatus()).isEqualTo(422);
    assertThat((String) JsonPath.read(out.getResponse().getContentAsString(), "$.error")).isEqualTo("fuera_de_zona");
    assertThat(ops(patch("/api/ops/barrio/reports/{id}", id)
        .contentType(MediaType.APPLICATION_JSON).content("{\"category\":\"nada\"}"))
        .getResponse().getStatus()).isEqualTo(400);
  }

  // ------------------------------------------------------------------- cierre

  @Test
  void cierreConFotoAprobadoDejaElReporteResueltoConSusDias() throws Exception {
    long id = create("bache", -34.8000, -55.9200);
    // Sobre un reporte aún no publicado: 409
    mvc.perform(multipart("/api/public/barrio/reports/{id}/closure", id)
            .file(new MockMultipartFile("photo", "a.jpg", "image/jpeg", BarrioTestImages.jpeg(100, 100))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.error").value("no_publicado"));
    approve(id);
    // Sin foto: 400
    mvc.perform(multipart("/api/public/barrio/reports/{id}/closure", id)).andExpect(status().isBadRequest());
    mvc.perform(multipart("/api/public/barrio/reports/{id}/closure", 999_999_001L)
            .file(new MockMultipartFile("photo", "a.jpg", "image/jpeg", BarrioTestImages.jpeg(100, 100))))
        .andExpect(status().isNotFound());

    BarrioReport r = reports.findById(id).orElseThrow();
    r.setCreatedAt(OffsetDateTime.now().minusDays(5).minusHours(2));
    reports.save(r);

    long claim = closure(id);
    // Pendiente: el reporte sigue abierto y el cierre no es público.
    mvc.perform(get("/api/public/barrio/reports/{id}", id)).andExpect(jsonPath("$.status").value("PUBLISHED"));
    String pending = ops(get("/api/ops/barrio/pending")).getResponse().getContentAsString();
    assertThat((java.util.List<String>) JsonPath.read(pending, "$.closures[?(@.id==" + claim + ")].comment"))
        .containsExactly("Ya lo taparon, gracias");
    assertThat((java.util.List<Number>) JsonPath.read(pending, "$.closures[?(@.id==" + claim + ")].report.id"))
        .containsExactly((int) id);

    MvcResult done = ops(post("/api/ops/barrio/closures/{id}/approve", claim));
    assertThat(done.getResponse().getStatus()).isEqualTo(200);

    mvc.perform(get("/api/public/barrio/reports/{id}", id))
        .andExpect(jsonPath("$.status").value("RESOLVED"))
        .andExpect(jsonPath("$.daysToResolve").value(5))
        .andExpect(jsonPath("$.daysOpen").value(5))
        .andExpect(jsonPath("$.resolvedAt").exists())
        .andExpect(jsonPath("$.closurePhotoUrl").value(containsString("/uploads/barrio/")));
    String resolved = mvc.perform(get("/api/public/barrio/reports?status=resolved")).andReturn().getResponse().getContentAsString();
    assertThat((java.util.List<Number>) JsonPath.read(resolved, "$[?(@.id==" + id + ")].id")).hasSize(1);
    String open = mvc.perform(get("/api/public/barrio/reports")).andReturn().getResponse().getContentAsString();
    assertThat((java.util.List<Number>) JsonPath.read(open, "$[?(@.id==" + id + ")].id")).isEmpty();
    // y ya no se puede volver a cerrar
    mvc.perform(multipart("/api/public/barrio/reports/{id}/closure", id)
            .file(new MockMultipartFile("photo", "a.jpg", "image/jpeg", BarrioTestImages.jpeg(100, 100))))
        .andExpect(status().isConflict());
  }

  @Test
  void cierreRechazadoDejaElReporteAbierto() throws Exception {
    long id = create("poda", -34.7800, -55.8800);
    approve(id);
    long claim = closure(id);
    assertThat(ops(post("/api/ops/barrio/closures/{id}/reject", claim)).getResponse().getStatus()).isEqualTo(200);
    mvc.perform(get("/api/public/barrio/reports/{id}", id)).andExpect(jsonPath("$.status").value("PUBLISHED"));
    assertThat(ops(post("/api/ops/barrio/closures/{id}/approve", claim)).getResponse().getStatus()).isEqualTo(409);
  }

  // -------------------------------------------------------------------- stats

  @Test
  void statsCuentaAbiertosYResueltosPorCategoriaYBarrio() throws Exception {
    String before = mvc.perform(get("/api/public/barrio/stats")).andReturn().getResponse().getContentAsString();
    int openBefore = JsonPath.read(before, "$.open");
    int resolvedBefore = JsonPath.read(before, "$.resolved");
    int lumOpenBefore = JsonPath.read(before, "$.byCategory.luminaria.open");

    long a = create("luminaria", -34.8750, -56.0800);
    approve(a);
    long b = create("luminaria", -34.8750, -56.0700);   // ~900 m: no se funden
    approve(b);
    closure(b);
    ops(post("/api/ops/barrio/closures/{id}/approve", closure(b)));

    mvc.perform(get("/api/public/barrio/stats"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.open").value(openBefore + 1))
        .andExpect(jsonPath("$.resolved").value(resolvedBefore + 1))
        .andExpect(jsonPath("$.byCategory.luminaria.open").value(lumOpenBefore + 1))
        .andExpect(jsonPath("$.byCategory.bache").exists())
        .andExpect(jsonPath("$.byCategory.poda").exists())
        .andExpect(jsonPath("$.avgDaysOpen").isNumber())
        .andExpect(jsonPath("$.byZone[0].zone").exists())
        .andExpect(content().string(not(containsString("phone"))));
  }

  // ---------------------------------------------------------------------- OG

  @Test
  void ogDelReclamoTraTituloConDiasYFotoYNuncaLaDescripcion() throws Exception {
    long id = create("bache", -34.8350, -55.9800); // Solymar
    approve(id);
    String html = mvc.perform(get("/og/barrio/r/{id}", id))
        .andExpect(status().isOk())
        .andReturn().getResponse().getContentAsString();
    long target = ((Number) JsonPath.read(
        mvc.perform(get("/api/public/barrio/reports/{id}", id)).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    assertThat(html).contains("<meta property=\"og:title\" content=\"Bache en Solymar — lleva 0 días\"")
        .contains("og:image\" content=\"https://api.fixy.com.uy/uploads/barrio/")
        .contains("og:url\" content=\"https://avisa.fixy.com.uy/r/" + target + "\"")
        .doesNotContain("Ramón");
  }

  @Test
  void ogDeUnReclamoNoPublicoOInexistenteEsElShellGenericoConStatus200() throws Exception {
    long pending = create("poda", -34.7700, -55.8300);
    mvc.perform(get("/og/barrio/r/{id}", pending)).andExpect(status().isOk())
        .andExpect(content().string(not(containsString("og:title"))));
    mvc.perform(get("/og/barrio/r/{id}", 999_999_002L)).andExpect(status().isOk())
        .andExpect(content().string(not(containsString("og:title"))));
  }
}
