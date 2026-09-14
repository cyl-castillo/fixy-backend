package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.BusinessRepository;
import com.fixy.backend.repository.OfferRepository;
import com.fixy.backend.service.BusinessInquiryExpiryScheduler;
import com.fixy.backend.service.MerchantOfferExpiryScheduler;
import com.fixy.backend.service.OfferDigestAutoScheduler;
import com.fixy.backend.service.OfferExpirationScheduler;
import com.fixy.backend.service.SavedOfferReminderScheduler;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Congelar ofertas y comercios (Refundación fase 1, contrato §6):
 * {@code fixy.offers.enabled=false}. Los 5 schedulers no procesan, las altas
 * y consultas públicas de oferta/comercio responden 503, {@code /count}
 * vuelve {@code {count:0}} aunque haya ofertas ACTIVE, y las lecturas
 * públicas (list/detalle) siguen funcionando.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "fixy.offers.enabled=false")
class OffersFrozenTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private BusinessRepository businessRepository;
  @Autowired private OfferRepository offerRepository;

  @Autowired private OfferExpirationScheduler offerExpirationScheduler;
  @Autowired private OfferDigestAutoScheduler offerDigestAutoScheduler;
  @Autowired private SavedOfferReminderScheduler savedOfferReminderScheduler;
  @Autowired private MerchantOfferExpiryScheduler merchantOfferExpiryScheduler;
  @Autowired private BusinessInquiryExpiryScheduler businessInquiryExpiryScheduler;

  private Offer persistActiveOffer() {
    Business business = new Business();
    business.setName("Comercio congelado test");
    business.setWhatsappNumber("098555999");
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business = businessRepository.save(business);

    Offer offer = new Offer();
    offer.setBusinessId(business.getId());
    offer.setTitle("Oferta congelada test");
    offer.setCategory("otro");
    offer.setZone("Solymar");
    offer.setDiscountText("20% off");
    offer.setStatus(OfferStatus.ACTIVE);
    offer.setValidUntil(OffsetDateTime.now().plusDays(5));
    return offerRepository.save(offer);
  }

  @Test
  void altaPublicaDeOfertas_devuelve503() throws Exception {
    mockMvc.perform(post("/api/public/offer-submissions")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void consultaDeOferta_devuelve503() throws Exception {
    Offer offer = persistActiveOffer();

    mockMvc.perform(post("/api/public/offers/{id}/inquiries", offer.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void consultaDeComercio_devuelve503() throws Exception {
    Business business = new Business();
    business.setName("Comercio inquiry congelado");
    business.setWhatsappNumber("098555888");
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    business = businessRepository.save(business);

    mockMvc.perform(post("/api/public/businesses/{id}/inquiries", business.getId())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void countDevuelveCeroAunqueHayaOfertasActivas() throws Exception {
    persistActiveOffer();

    mockMvc.perform(get("/api/public/offers/count"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.count").value(0));
  }

  @Test
  void lecturaPublicaDeListadoYDetalleSigueFuncionando() throws Exception {
    Offer offer = persistActiveOffer();

    mockMvc.perform(get("/api/public/offers")).andExpect(status().isOk());
    mockMvc.perform(get("/api/public/offers/{id}", offer.getId())).andExpect(status().isOk());
  }

  @Test
  void los5SchedulersNoProcesanNada() {
    assertThat(offerExpirationScheduler.processOnce()).isZero();
    assertThat(offerDigestAutoScheduler.processOnce()).isNull();
    assertThat(savedOfferReminderScheduler.processOnce()).isZero();
    assertThat(merchantOfferExpiryScheduler.processOnce()).isZero();
    assertThat(businessInquiryExpiryScheduler.processOnce()).isZero();
  }
}
