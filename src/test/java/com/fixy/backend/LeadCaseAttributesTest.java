package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import com.jayway.jsonpath.JsonPath;
import jakarta.persistence.EntityManager;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Core Fase 3 (CORE_FASE3_CONTRATO.md, pieza 2): Lead como "Case" — {@code caseType} y
 * {@code attributes} persistidos de verdad contra H2, no solo en memoria. Cada test hace
 * {@code flush + clear} antes de leer para que la lectura venga de la base y no del contexto
 * de persistencia.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class LeadCaseAttributesTest {

  @Autowired private LeadRepository leadRepository;
  @Autowired private ServiceCatalogItemRepository serviceCatalogItemRepository;
  @Autowired private EntityManager entityManager;
  @Autowired private JdbcTemplate jdbcTemplate;
  @Autowired private MockMvc mockMvc;

  private Lead newLead() {
    Lead lead = new Lead();
    lead.setProblem("[smoke] caso de prueba de attributes");
    lead.setChannel("test");
    return lead;
  }

  private Lead reload(Long id) {
    entityManager.flush();
    entityManager.clear();
    return leadRepository.findById(id).orElseThrow();
  }

  @Test
  void unLeadNuevoNaceConElCaseTypeDelPlaybookYAttributesVacio() {
    Long id = leadRepository.save(newLead()).getId();

    Lead loaded = reload(id);
    assertThat(loaded.getCaseType()).isEqualTo("home-services");
    assertThat(loaded.getAttributes()).isEmpty();
  }

  @Test
  void attributesHaceRoundTripConStringNumeroBooleanoYAnidadoLargo() {
    String largo = "x".repeat(400);
    Map<String, Object> attributes = new LinkedHashMap<>();
    attributes.put("texto", "hola");
    attributes.put("numero", 42);
    attributes.put("decimal", 2.5);
    attributes.put("booleano", true);
    attributes.put("anidado", Map.of("lista", List.of(1, 2, 3), "largo", largo));
    Lead lead = newLead();
    lead.setAttributes(attributes);
    Long id = leadRepository.save(lead).getId();

    Lead loaded = reload(id);
    assertThat(loaded.getAttributes()).isEqualTo(attributes);

    // Y la columna no es varchar(255): el JSON guardado pasa de 300 chars.
    Integer length = jdbcTemplate.queryForObject(
        "select length(attributes) from leads where id = ?", Integer.class, id);
    assertThat(length).isGreaterThan(400);
  }

  @Test
  void modificarElMapaYGuardarActualizaLaColumna() {
    Long id = leadRepository.save(newLead()).getId();

    Lead loaded = reload(id);
    Map<String, Object> updated = new LinkedHashMap<>(loaded.getAttributes());
    updated.put("clave", "valor");
    loaded.setAttributes(updated);
    leadRepository.save(loaded);

    assertThat(reload(id).getAttributes()).containsEntry("clave", "valor");
  }

  @Test
  void jsonRotoEnLaBaseSeLeeComoInvalidSinExcepcionYElTextoSobreviveAlReGuardar() {
    Long id = leadRepository.save(newLead()).getId();
    entityManager.flush();
    jdbcTemplate.update("update leads set attributes = ? where id = ?", "{esto no es json", id);
    entityManager.clear();

    Lead loaded = leadRepository.findById(id).orElseThrow();
    assertThat(loaded.getAttributes()).containsOnlyKeys("_invalid");
    assertThat(loaded.getAttributes().get("_invalid")).isEqualTo("{esto no es json");

    // Re-guardar el lead (sin @DynamicUpdate se escriben todas las columnas) no pierde el texto.
    loaded.setNotes("tocado");
    leadRepository.save(loaded);
    entityManager.flush();
    String raw = jdbcTemplate.queryForObject("select attributes from leads where id = ?", String.class, id);
    assertThat(raw).contains("esto no es json");
    assertThat(reload(id).getAttributes().get("_invalid")).isEqualTo("{esto no es json");
  }

  @Test
  void prePersistPonePorDefectoCaseTypeYAttributesNull() {
    Lead lead = newLead();
    lead.setCaseType(null);
    lead.setAttributes(null);
    Long id = leadRepository.save(lead).getId();

    Lead loaded = reload(id);
    assertThat(loaded.getCaseType()).isEqualTo("home-services");
    assertThat(loaded.getAttributes()).isEmpty();
  }

  @Test
  void unLeadCreadoPorPostChatsNaceConCaseTypeHomeServices() throws Exception {
    MvcResult chat = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"channel\":\"web-chat\"}"))
        .andExpect(status().is2xxSuccessful())
        .andReturn();
    Integer leadId = JsonPath.read(chat.getResponse().getContentAsString(), "$.id");

    Lead loaded = reload(Long.valueOf(leadId));
    assertThat(loaded.getCaseType()).isEqualTo("home-services");
    assertThat(loaded.getAttributes()).isEmpty();
  }

  @Test
  void unLeadCreadoPorPostOrdersNaceConCaseTypeHomeServices() throws Exception {
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory("aires_acondicionados");
    item.setCode("svc-case-aire");
    item.setName("Service de split");
    item.setPriceFrom(2900);
    item.setActive(true);
    serviceCatalogItemRepository.save(item);

    MvcResult order = mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"serviceCode": "svc-case-aire", "zone": "Lagomar", "timeWindow": "manana_am",
                 "name": "Ana", "phone": "099123456", "notes": "caso de prueba", "channel": "web-order"}
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(order.getResponse().getContentAsString(), "$.leadId");

    Lead loaded = reload(Long.valueOf(leadId));
    assertThat(loaded.getCaseType()).isEqualTo("home-services");
    assertThat(loaded.getAttributes()).isEmpty();
  }
}
