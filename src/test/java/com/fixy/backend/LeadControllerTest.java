package com.fixy.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

@SpringBootTest
@AutoConfigureMockMvc
class LeadControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void shouldCreateListAndUpdateLead() throws Exception {
    String providerPayload = """
        {
          "name": "Proveedor Demo",
          "phone": "093000000",
          "primaryZone": "Montevideo",
          "city": "Montevideo",
          "categories": "plomeria"
        }
        """;

    mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(providerPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Proveedor Demo"));

    String createPayload = """
        {
          "name": "Lucia",
          "phone": "093551242",
          "problem": "Se rompio la ducha y pierde agua sin parar en Montevideo",
          "channel": "whatsapp"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/leads")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.status").value("NEW"))
        .andExpect(jsonPath("$.detectedCategory").value("plomeria"))
        .andExpect(jsonPath("$.summary").exists())
        .andExpect(jsonPath("$.missingFields").isArray())
        .andExpect(jsonPath("$.blockingFields").isArray())
        .andExpect(jsonPath("$.nextRecommendedAction").exists())
        .andReturn();

    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(get("/api/leads")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].phone").value("093551242"));

    String updatePayload = """
        {
          "status": "ASSIGNED",
          "assignedProvider": "Proveedor Demo"
        }
        """;

    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(updatePayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("ASSIGNED"))
        .andExpect(jsonPath("$.assignedProvider").value("Proveedor Demo"));
  }

  @Test
  void shouldSupportPublicConversationalFlowAndMatches() throws Exception {
    String providerPayload = """
        {
          "name": "Electricista Costa",
          "phone": "099000111",
          "primaryZone": "Pocitos",
          "city": "Montevideo",
          "categories": "electricidad"
        }
        """;

    mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(providerPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Electricista Costa"));

    String createPayload = """
        {
          "name": "Carlos",
          "phone": "099123123",
          "problem": "Necesito electricista urgente",
          "channel": "web"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.detectedCategory").value("electricidad"))
        .andExpect(jsonPath("$.readyForMatching").value(false))
        .andExpect(jsonPath("$.blockingFields[0]").value("zona"))
        .andExpect(jsonPath("$.nextRecommendedAction").value("ask_location"))
        .andReturn();

    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(post("/api/public/leads/{id}/matches", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matches").isArray())
        .andExpect(jsonPath("$.matches.length()").value(0))
        .andExpect(jsonPath("$.blockingFields[0]").value("zona"))
        .andExpect(jsonPath("$.nextRecommendedAction").value("ask_location"));

    String enrichPayload = """
        {
          "location": "Pocitos",
          "notes": "Es en apartamento, salta la llave general"
        }
        """;

    mockMvc.perform(patch("/api/public/leads/{id}/context", leadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(enrichPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location").value("Pocitos"))
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andExpect(jsonPath("$.missingFields.length()").value(2))
        .andExpect(jsonPath("$.blockingFields").isArray())
        .andExpect(jsonPath("$.blockingFields.length()").value(0))
        .andExpect(jsonPath("$.nextRecommendedAction").value("generate_matches"));

    mockMvc.perform(post("/api/public/leads/{id}/matches", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matches[0].name").value("Electricista Costa"))
        .andExpect(jsonPath("$.matches[0].score").value(80))
        .andExpect(jsonPath("$.matches[0].reasons[0]").value("categoria_coincide"))
        .andExpect(jsonPath("$.nextRecommendedAction").value("present_matches"));

    mockMvc.perform(get("/api/public/leads/{id}", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location").value("Pocitos"))
        .andExpect(jsonPath("$.summary").exists());

    mockMvc.perform(get("/api/public/leads/{id}/timeline", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].type").value("LEAD_CREATED"))
        .andExpect(jsonPath("$[1].type").value("INTAKE_CLASSIFIED"))
        .andExpect(jsonPath("$[2].type").value("MATCH_BLOCKED"))
        .andExpect(jsonPath("$[3].type").value("CONTEXT_UPDATED"))
        .andExpect(jsonPath("$[4].type").value("INTAKE_CLASSIFIED"))
        .andExpect(jsonPath("$[5].type").value("MATCH_GENERATED"));

    mockMvc.perform(post("/api/public/leads/{id}/matches", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.lead.location").value("Pocitos"))
        .andExpect(jsonPath("$.lead.readyForMatching").value(true));
  }

  @Test
  void shouldCreatePublicLeadWithStructuredIntakeFields() throws Exception {
    String createPayload = """
        {
          "name": "Marta",
          "phone": "098222333",
          "problem": "Necesito ayuda con algo en casa.",
          "channel": "web-app",
          "serviceCategory": "plomeria",
          "zone": "Solymar",
          "urgency": "hoy",
          "address": "Av. Principal y calle 3",
          "details": "Pierde agua abajo de la pileta"
        }
        """;

    mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Marta"))
        .andExpect(jsonPath("$.detectedCategory").value("plomeria"))
        .andExpect(jsonPath("$.location").value("Solymar"))
        .andExpect(jsonPath("$.urgency").value("media"))
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andExpect(jsonPath("$.blockingFields.length()").value(0))
        .andExpect(jsonPath("$.nextRecommendedAction").value("generate_matches"))
        .andExpect(jsonPath("$.suggestedReply").exists());
  }

  @Test
  void shouldRegisterPublicProviderLeadWithProviderSpecificReadiness() throws Exception {
    String payload = """
        {
          "name": "Ana",
          "phone": "099888777",
          "message": "Soy electricista, trabajo en Pocitos y tengo disponibilidad para urgencias",
          "channel": "web-provider"
        }
        """;

    mockMvc.perform(post("/api/public/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Ana"))
        .andExpect(jsonPath("$.category").value("electricidad"))
        .andExpect(jsonPath("$.zone").value("Pocitos"))
        .andExpect(jsonPath("$.missingFields.length()").value(0))
        .andExpect(jsonPath("$.readyForReview").value(true))
        .andExpect(jsonPath("$.nextRecommendedAction").value("ready_for_review"))
        .andExpect(jsonPath("$.suggestedReply").value("Perfecto. Ya tengo suficiente para dejar tu perfil listo para revisión interna."));
  }

  @Test
  void shouldReturnStructuredErrorsForPublicValidation() throws Exception {
    String invalidPayload = """
        {
          "name": "Pepe",
          "problem": "corto"
        }
        """;

    mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidPayload))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.code").value("request_failed"))
        .andExpect(jsonPath("$.error.message").exists())
        .andExpect(jsonPath("$.error.retryable").value(false));
  }
}
