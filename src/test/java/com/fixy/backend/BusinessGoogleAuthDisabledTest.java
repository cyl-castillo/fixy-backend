package com.fixy.backend;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

/**
 * Sin GOOGLE_CLIENT_ID configurado, el link/login de Google del DUEÑO DEL
 * COMERCIO queda disabled: 503 con mensaje claro en vez de 401/500 — mismo
 * patrón que {@code GoogleAuthDisabledTest} (contexto Spring separado por
 * distinta property, para no interferir con la suite general que corre con
 * el flag habilitado). Deliberadamente distinto del precedente de
 * proveedor (que no distingue "disabled" de "credential inválido"): ver
 * javadoc de {@code BusinessGoogleAuthService}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(properties = "fixy.auth.google-client-id=")
class BusinessGoogleAuthDisabledTest {

  @Autowired
  private MockMvc mockMvc;

  @Autowired
  private BusinessRepository businessRepository;

  private Business persistBusiness() {
    Business business = new Business();
    business.setName("Comercio Google Disabled Test");
    business.setWhatsappNumber("099888777");
    business.setCategory("otro");
    business.setPrimaryZone("Solymar");
    business.setStatus(BusinessStatus.ACTIVE);
    business.setPanelToken("panel-google-disabled-token");
    return businessRepository.save(business);
  }

  @Test
  void linkGoogleReturns503WhenNotConfigured() throws Exception {
    Business business = persistBusiness();
    mockMvc.perform(post("/api/public/merchant/{token}/link-google", business.getPanelToken())
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cualquier-token\"}"))
        .andExpect(status().isServiceUnavailable());
  }

  @Test
  void loginGoogleBusinessReturns503WhenNotConfigured() throws Exception {
    mockMvc.perform(post("/api/public/auth/google-business")
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"credential\": \"cualquier-token\"}"))
        .andExpect(status().isServiceUnavailable());
  }
}
