package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@code POST /api/public/orders} comparte el rate-limit de {@code
 * PublicLeadAbuseProtectionService.validate} con {@code POST
 * /api/public/leads} (contrato §3, "mismo rate-limit y anti-abuso"). Ventana
 * achicada acá vía {@code @TestPropertySource} (contexto propio, contadores
 * en memoria limpios) para provocar el 429 sin 200+ requests.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = {
    "fixy.abuse.max-requests-per-window=2",
    "fixy.abuse.window-seconds=600"
})
class PublicOrderRateLimitTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private ServiceCatalogItemRepository serviceCatalogItemRepository;

  @Test
  void excedeElRateLimitCompartidoConLeads_devuelve429() throws Exception {
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory("plomeria");
    item.setCode("svc-ratelimit-test");
    item.setName("Visita de plomería");
    item.setPriceFrom(700);
    item.setActive(true);
    serviceCatalogItemRepository.save(item);

    String body = """
        {"serviceCode": "svc-ratelimit-test", "zone": "Solymar", "timeWindow": "hoy",
         "name": "Ana", "phone": "099123456"}
        """;

    for (int i = 0; i < 2; i++) {
      mockMvc.perform(post("/api/public/orders")
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isCreated());
    }

    mockMvc.perform(post("/api/public/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(body))
        .andExpect(status().isTooManyRequests());
  }
}
