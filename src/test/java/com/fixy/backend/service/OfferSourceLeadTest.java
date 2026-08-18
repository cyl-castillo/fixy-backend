package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.OfferRepository;
import com.jayway.jsonpath.JsonPath;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Ruta proveedor del CTA de ofertas (FIXY_OFERTAS_CTA_DESIGN.md §3.2):
 * vínculo oferta→lead vía {@code sourceOfferId} para medir conversión.
 * Cada aserción filtra por los ids creados en ESTE test (H2 compartida
 * entre contextos).
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class OfferSourceLeadTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private OfferRepository offerRepository;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private LeadRepository leadRepository;

  private Business persistBusiness(String name, Long providerId) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber("098666" + System.nanoTime() % 1000);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setProviderId(providerId);
    return businessRepository.save(business);
  }

  private Offer persistOffer(Business business) {
    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle("Oferta source lead test " + business.getId());
    offer.setCategory("otro");
    offer.setZone("Solymar");
    offer.setStatus(OfferStatus.ACTIVE);
    offer.setValidUntil(OffsetDateTime.now().plusDays(5));
    return offerRepository.save(offer);
  }

  @Test
  void chatStartConSourceOfferIdPersisteElVinculoEnElLead() throws Exception {
    Business business = persistBusiness("Comercio Source Lead Test", 55L);
    Offer offer = persistOffer(business);

    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceOfferId\": %d}".formatted(offer.getId())))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.id")).longValue();

    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.getSourceOfferId()).isEqualTo(offer.getId());
  }

  @Test
  void chatStartSinSourceOfferIdDejaElCampoNull() throws Exception {
    MvcResult res = mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isCreated())
        .andReturn();
    Long leadId = ((Number) JsonPath.read(res.getResponse().getContentAsString(), "$.id")).longValue();

    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.getSourceOfferId()).isNull();
  }

  @Test
  void leadCountEnOfferResponseAdminReflejaLosLeadsConEseSourceOfferId() throws Exception {
    Business business = persistBusiness("Comercio Lead Count Test", 56L);
    Offer offer = persistOffer(business);

    mockMvc.perform(get("/api/offers/{id}", offer.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.leadCount").value(0));

    mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceOfferId\": %d}".formatted(offer.getId())))
        .andExpect(status().isCreated());
    mockMvc.perform(post("/api/public/chats")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"sourceOfferId\": %d}".formatted(offer.getId())))
        .andExpect(status().isCreated());

    mockMvc.perform(get("/api/offers/{id}", offer.getId())
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.leadCount").value(2));
  }
}
