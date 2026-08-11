package com.fixy.backend.service;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * Upload de foto para {@code Offer} (mismo patrón de storage que las fotos
 * de lead — uploads/ + urlPrefix, validación de tipo/tamaño). Sin foto no
 * hay demo que impresione: es la pieza que Carlos necesita para mostrar la
 * vista previa de la oferta con una imagen real.
 */
@SpringBootTest
@AutoConfigureMockMvc
class OfferPhotoUploadTest {

  @Autowired private MockMvc mockMvc;

  private static final byte[] JPEG_BYTES = new byte[]{
      (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10,
      'J', 'F', 'I', 'F', 0, 1, 1, 0, 0, 1, 0, 1, 0, 0, (byte) 0xFF, (byte) 0xD9
  };

  private Integer createBusiness(String whatsapp) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/businesses")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"name": "Comercio Foto Test", "whatsappNumber": "%s", "category": "otro"}
                """.formatted(whatsapp)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
  }

  private Integer createOffer(Integer businessId) throws Exception {
    MvcResult res = mockMvc.perform(post("/api/offers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"businessId": %d, "title": "Oferta con foto", "category": "otro", "zone": "Solymar"}
                """.formatted(businessId)))
        .andExpect(status().isCreated())
        .andReturn();
    return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
  }

  @Test
  void subeUnaFotoValidaYQuedaComoPhotoUrlDeLaOferta() throws Exception {
    Integer businessId = createBusiness("098333001");
    Integer offerId = createOffer(businessId);

    MockMultipartFile file = new MockMultipartFile("file", "oferta.jpg", "image/jpeg", JPEG_BYTES);

    mockMvc.perform(multipart("/api/offers/{id}/photo", offerId)
            .file(file)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.photoUrl").value(Matchers.containsString("/uploads/offer-" + offerId + "/")));
  }

  @Test
  void subirUnaSegundaFotoReemplazaLaAnterior() throws Exception {
    Integer businessId = createBusiness("098333002");
    Integer offerId = createOffer(businessId);

    MockMultipartFile first = new MockMultipartFile("file", "primera.jpg", "image/jpeg", JPEG_BYTES);
    MvcResult res1 = mockMvc.perform(multipart("/api/offers/{id}/photo", offerId)
            .file(first)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String firstUrl = JsonPath.read(res1.getResponse().getContentAsString(), "$.photoUrl");

    MockMultipartFile second = new MockMultipartFile("file", "segunda.jpg", "image/jpeg", JPEG_BYTES);
    MvcResult res2 = mockMvc.perform(multipart("/api/offers/{id}/photo", offerId)
            .file(second)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String secondUrl = JsonPath.read(res2.getResponse().getContentAsString(), "$.photoUrl");

    org.assertj.core.api.Assertions.assertThat(secondUrl).isNotEqualTo(firstUrl);
  }

  @Test
  void rechazaFormatoNoPermitido() throws Exception {
    Integer businessId = createBusiness("098333003");
    Integer offerId = createOffer(businessId);

    MockMultipartFile bad = new MockMultipartFile(
        "file", "doc.pdf", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46});

    mockMvc.perform(multipart("/api/offers/{id}/photo", offerId)
            .file(bad)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void rechazaOfertaInexistente() throws Exception {
    MockMultipartFile file = new MockMultipartFile("file", "oferta.jpg", "image/jpeg", JPEG_BYTES);

    mockMvc.perform(multipart("/api/offers/{id}/photo", 999999)
            .file(file)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isNotFound());
  }

  @Test
  void requiereAutenticacion() throws Exception {
    Integer businessId = createBusiness("098333004");
    Integer offerId = createOffer(businessId);

    MockMultipartFile file = new MockMultipartFile("file", "oferta.jpg", "image/jpeg", JPEG_BYTES);

    mockMvc.perform(multipart("/api/offers/{id}/photo", offerId).file(file))
        .andExpect(status().isUnauthorized());
  }
}
