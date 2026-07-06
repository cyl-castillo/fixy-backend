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
          "primaryZone": "Solymar",
          "city": "Ciudad de la Costa",
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
          "problem": "Se rompio la ducha y pierde agua sin parar en Solymar",
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
          "name": "Jardines Costa",
          "phone": "099000111",
          "primaryZone": "Lagomar",
          "coverageZones": "Lagomar, Solymar",
          "city": "Ciudad de la Costa",
          "categories": "jardineria"
        }
        """;

    mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(providerPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Jardines Costa"));

    String createPayload = """
        {
          "name": "Carlos",
          "phone": "099123123",
          "problem": "Necesito cortar pasto y ordenar el jardin",
          "channel": "web"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.detectedCategory").value("jardineria"))
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
          "location": "Lagomar",
          "notes": "Jardin crecido, necesita corte de pasto"
        }
        """;

    mockMvc.perform(patch("/api/public/leads/{id}/context", leadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(enrichPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location").value("Lagomar"))
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andExpect(jsonPath("$.blockingFields").isArray())
        .andExpect(jsonPath("$.blockingFields.length()").value(0))
        .andExpect(jsonPath("$.nextRecommendedAction").value("generate_matches"));

    mockMvc.perform(post("/api/public/leads/{id}/matches", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matches[0].name").value("Jardines del Este"))
        .andExpect(jsonPath("$.matches[0].score").value(100))
        .andExpect(jsonPath("$.matches[0].reasons[0]").value("categoria_coincide"))
        .andExpect(jsonPath("$.nextRecommendedAction").value("present_matches"));

    mockMvc.perform(get("/api/public/leads/{id}", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.location").value("Lagomar"))
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
        .andExpect(jsonPath("$.lead.location").value("Lagomar"))
        .andExpect(jsonPath("$.lead.readyForMatching").value(true));
  }

  @Test
  void shouldMatchAirConditioningWithinMvpArea() throws Exception {
    String providerPayload = """
        {
          "name": "Aires Costa Test",
          "phone": "099444555",
          "primaryZone": "Ciudad de la Costa",
          "coverageZones": "Lagomar, Solymar, El Pinar",
          "city": "Ciudad de la Costa",
          "categories": "aires_acondicionados"
        }
        """;

    mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(providerPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Aires Costa Test"));

    String createPayload = """
        {
          "name": "Carlos",
          "phone": "099123123",
          "problem": "El aire acondicionado split no enfria",
          "channel": "web",
          "serviceCategory": "aires acondicionados",
          "zone": "Lagomar"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.detectedCategory").value("aires_acondicionados"))
        .andExpect(jsonPath("$.location").value("Lagomar"))
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andExpect(jsonPath("$.blockingFields.length()").value(0))
        .andExpect(jsonPath("$.nextRecommendedAction").value("generate_matches"))
        .andReturn();

    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(post("/api/public/leads/{id}/matches", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matches[0].category").value("aires_acondicionados"))
        .andExpect(jsonPath("$.matches[0].reasons[0]").value("categoria_coincide"))
        .andExpect(jsonPath("$.nextRecommendedAction").value("present_matches"));
  }

  @Test
  void shouldBlockOutOfMvpCategoryAndArea() throws Exception {
    String createPayload = """
        {
          "name": "Carlos",
          "phone": "099123123",
          "problem": "Necesito electricista urgente",
          "channel": "web",
          "serviceCategory": "electricidad",
          "zone": "Pocitos"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.detectedCategory").value("electricidad"))
        .andExpect(jsonPath("$.location").value("Pocitos"))
        .andExpect(jsonPath("$.readyForMatching").value(false))
        .andExpect(jsonPath("$.blockingFields[0]").value("categoria_fuera_de_alcance"))
        .andExpect(jsonPath("$.blockingFields[1]").value("zona_fuera_de_cobertura"))
        .andExpect(jsonPath("$.nextRecommendedAction").value("out_of_scope_category"))
        .andReturn();

    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

    mockMvc.perform(post("/api/public/leads/{id}/matches", leadId))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.matches.length()").value(0))
        .andExpect(jsonPath("$.blockingFields[0]").value("categoria_fuera_de_alcance"))
        .andExpect(jsonPath("$.nextRecommendedAction").value("out_of_scope_category"));
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
          "message": "Soy jardinero, trabajo en Solymar y tengo disponibilidad para coordinar servicios",
          "channel": "web-provider"
        }
        """;

    mockMvc.perform(post("/api/public/providers")
            .contentType(MediaType.APPLICATION_JSON)
            .content(payload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.name").value("Ana"))
        .andExpect(jsonPath("$.category").value("jardineria"))
        .andExpect(jsonPath("$.zone").value("Solymar"))
        .andExpect(jsonPath("$.missingFields.length()").value(0))
        .andExpect(jsonPath("$.readyForReview").value(true))
        .andExpect(jsonPath("$.nextRecommendedAction").value("ready_for_review"))
        .andExpect(jsonPath("$.suggestedReply").value("Perfecto. Ya tengo suficiente para dejar tu perfil listo para revisión interna."));
  }

  @Test
  void shouldAcceptStructuredHintsOnPublicContextUpdate() throws Exception {
    String createPayload = """
        {
          "name": "Lucia",
          "phone": "099123456",
          "problem": "Necesito ayuda con algo en mi casa, no se que es",
          "channel": "web-app"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.readyForMatching").value(false))
        .andReturn();

    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

    String enrichPayload = """
        {
          "location": "Solymar",
          "serviceCategory": "plomeria",
          "urgency": "hoy",
          "address": "Av. Giannattasio km 25",
          "details": "perdida de agua en cocina"
        }
        """;

    mockMvc.perform(patch("/api/public/leads/{id}/context", leadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(enrichPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.detectedCategory").value("plomeria"))
        .andExpect(jsonPath("$.location").value("Solymar"))
        .andExpect(jsonPath("$.readyForMatching").value(true))
        .andExpect(jsonPath("$.notes").value(org.hamcrest.Matchers.containsString("Av. Giannattasio km 25")))
        .andExpect(jsonPath("$.notes").value(org.hamcrest.Matchers.containsString("perdida de agua en cocina")))
        .andExpect(jsonPath("$.history").value(org.hamcrest.Matchers.containsString("Servicio sugerido")))
        .andExpect(jsonPath("$.history").value(org.hamcrest.Matchers.containsString("Urgencia sugerida")));
  }

  @Test
  void shouldRecordWaitlistInterestAsNotes() throws Exception {
    String createPayload = """
        {
          "phone": "099000111",
          "problem": "Necesito un electricista urgente para mi casa",
          "channel": "web-app",
          "serviceCategory": "electricidad",
          "zone": "Solymar"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.nextRecommendedAction").value("out_of_scope_category"))
        .andReturn();

    Integer leadId = JsonPath.read(createResult.getResponse().getContentAsString(), "$.id");

    String waitlistPayload = """
        {
          "notes": "Cliente quiere ser avisado: servicio fuera de cobertura (lucia@example.com)."
        }
        """;

    mockMvc.perform(patch("/api/public/leads/{id}/context", leadId)
            .contentType(MediaType.APPLICATION_JSON)
            .content(waitlistPayload))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.notes").value(org.hamcrest.Matchers.containsString("quiere ser avisado")))
        .andExpect(jsonPath("$.notes").value(org.hamcrest.Matchers.containsString("lucia@example.com")));
  }

  @Test
  void shouldExposePublicChatGuardedByAccessToken() throws Exception {
    String createPayload = """
        {
          "name": "Lucia",
          "phone": "099334455",
          "problem": "Necesito un plomero, perdida de agua en cocina",
          "channel": "web-app",
          "serviceCategory": "plomeria",
          "zone": "Solymar"
        }
        """;

    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.accessToken").exists())
        .andReturn();

    String body = createResult.getResponse().getContentAsString();
    Integer leadId = JsonPath.read(body, "$.id");
    String token = JsonPath.read(body, "$.accessToken");

    // Sin token: 403
    mockMvc.perform(get("/api/public/leads/{id}/messages", leadId))
        .andExpect(status().isBadRequest());

    // Token incorrecto: 403
    mockMvc.perform(get("/api/public/leads/{id}/messages", leadId)
            .param("token", "wrong-token"))
        .andExpect(status().isForbidden());

    // Token correcto, lista vacía
    mockMvc.perform(get("/api/public/leads/{id}/messages", leadId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));

    // Cliente manda un mensaje
    String customerMsg = """
        {"text": "¿Cuándo vienen aproximadamente?"}
        """;
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content(customerMsg))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sender").value("customer"))
        .andExpect(jsonPath("$.text").value("¿Cuándo vienen aproximadamente?"));

    // Ops responde como provider (autenticado)
    String providerMsg = """
        {"sender": "provider", "text": "Voy mañana a las 10."}
        """;
    mockMvc.perform(post("/api/leads/{id}/messages", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(providerMsg))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sender").value("provider"));

    // Cliente recibe ambos mensajes
    mockMvc.perform(get("/api/public/leads/{id}/messages", leadId)
            .param("token", token))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].sender").value("customer"))
        .andExpect(jsonPath("$[1].sender").value("provider"));

    // Cliente sin texto: 400
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"\"}"))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldRejectChatMessagesWithLinksAndRepeats() throws Exception {
    String createPayload = """
        {
          "phone": "099445566",
          "problem": "Necesito un plomero, perdida de agua importante",
          "channel": "web-app",
          "serviceCategory": "plomeria",
          "zone": "Solymar"
        }
        """;
    MvcResult createResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andReturn();
    String body = createResult.getResponse().getContentAsString();
    Integer leadId = JsonPath.read(body, "$.id");
    String token = JsonPath.read(body, "$.accessToken");

    // link http
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"mira esto https://malware.example/x\"}"))
        .andExpect(status().isBadRequest());

    // dominio sin protocolo
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"podés ver fixy.com.uy/algo\"}"))
        .andExpect(status().isBadRequest());

    // wa.me link
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"escribime wa.me/598999\"}"))
        .andExpect(status().isBadRequest());

    // mensaje normal: ok
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"buenas, cuándo pueden venir?\"}"))
        .andExpect(status().isCreated());

    // repetido idéntico
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"buenas, cuándo pueden venir?\"}"))
        .andExpect(status().isBadRequest());

    // mensaje distinto: ok
    mockMvc.perform(post("/api/public/leads/{id}/messages", leadId)
            .param("token", token)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"hola, quería saber el horario\"}"))
        .andExpect(status().isCreated());
  }

  @Test
  void shouldGiveProviderSelfServicePanel() throws Exception {
    String providerPayload = """
        {
          "name": "Plomeros Solymar",
          "phone": "099445566",
          "primaryZone": "Solymar",
          "city": "Ciudad de la Costa",
          "categories": "plomeria"
        }
        """;
    MvcResult providerResult = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(providerPayload))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(providerResult.getResponse().getContentAsString(), "$.id");

    // ops genera magic link
    MvcResult tokenResult = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.accessToken").exists())
        .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.matchesPattern(".*/p/" + providerId + "/[a-f0-9]+")))
        .andReturn();
    String providerToken = JsonPath.read(tokenResult.getResponse().getContentAsString(), "$.accessToken");

    // crear lead y asignar al proveedor
    String createPayload = """
        {
          "phone": "099334455",
          "problem": "Necesito plomero para perdida en cocina urgente",
          "channel": "web-app",
          "serviceCategory": "plomeria",
          "zone": "Solymar"
        }
        """;
    MvcResult leadResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(createPayload))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadResult.getResponse().getContentAsString(), "$.id");

    String assignPayload = """
        {
          "status": "ASSIGNED",
          "assignedProvider": "Plomeros Solymar"
        }
        """;
    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content(assignPayload))
        .andExpect(status().isOk());

    // proveedor consulta su panel
    mockMvc.perform(get("/api/public/providers/{id}/me", providerId)
            .param("token", providerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(providerId))
        .andExpect(jsonPath("$.assignedLeads.length()").value(1))
        .andExpect(jsonPath("$.assignedLeads[0].id").value(leadId))
        .andExpect(jsonPath("$.assignedLeads[0].accessToken").exists());

    // token incorrecto: 403
    mockMvc.perform(get("/api/public/providers/{id}/me", providerId)
            .param("token", "wrong"))
        .andExpect(status().isForbidden());

    // proveedor manda mensaje al cliente
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/messages", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"text\": \"Voy mañana 10am.\"}"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sender").value("provider"));

    // proveedor cambia status a IN_PROGRESS
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"IN_PROGRESS\"}"))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("IN_PROGRESS"));

    // status invalido para self-service
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, leadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"NEW\"}"))
        .andExpect(status().isBadRequest());

    // proveedor solo ve sus leads. Crear otro lead asignado a otro nombre.
    String otherLead = """
        {
          "phone": "099001122",
          "problem": "Necesito plomero por desague",
          "channel": "web-app",
          "serviceCategory": "plomeria",
          "zone": "Lagomar"
        }
        """;
    MvcResult otherResult = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content(otherLead))
        .andExpect(status().isCreated())
        .andReturn();
    Integer otherLeadId = JsonPath.read(otherResult.getResponse().getContentAsString(), "$.id");
    mockMvc.perform(patch("/api/leads/{id}", otherLeadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProvider\": \"Otro Proveedor\"}"))
        .andExpect(status().isOk());
    mockMvc.perform(post("/api/public/providers/{id}/leads/{lid}/status", providerId, otherLeadId)
            .param("token", providerToken)
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"IN_PROGRESS\"}"))
        .andExpect(status().isForbidden());
  }

  @Test
  void shouldAssignLeadToProviderByIdAndRouteToPanel() throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Plomería FK",
                  "phone": "099777888",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "plomeria"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    MvcResult tk = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String providerToken = JsonPath.read(tk.getResponse().getContentAsString(), "$.accessToken");

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099221122",
                  "problem": "Necesito plomero para verificacion FK",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");

    // Asignar por ID (NO mando assignedProvider string).
    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "status": "ASSIGNED",
                  "assignedProviderId": %d
                }
                """.formatted(providerId)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedProvider").value("Plomería FK"));

    // El provider lo ve en su panel (matcheo por id).
    mockMvc.perform(get("/api/public/providers/{id}/me", providerId)
            .param("token", providerToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.assignedLeads.length()").value(1))
        .andExpect(jsonPath("$.assignedLeads[0].id").value(leadId));
  }

  @Test
  void shouldLetProviderUploadPhotoAndCustomerReadIt() throws Exception {
    MvcResult prov = mockMvc.perform(post("/api/providers")
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "name": "Plomería Fotos",
                  "phone": "099556677",
                  "primaryZone": "Solymar",
                  "city": "Ciudad de la Costa",
                  "categories": "plomeria"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer providerId = JsonPath.read(prov.getResponse().getContentAsString(), "$.id");

    MvcResult tk = mockMvc.perform(post("/api/providers/{id}/access-token", providerId)
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andReturn();
    String providerToken = JsonPath.read(tk.getResponse().getContentAsString(), "$.accessToken");

    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099445599",
                  "problem": "Plomero urgente para foto test",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String leadToken = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.accessToken");

    mockMvc.perform(patch("/api/leads/{id}", leadId)
            .with(httpBasic("test-ops", "test-pass"))
            .contentType(MediaType.APPLICATION_JSON)
            .content("{\"status\": \"ASSIGNED\", \"assignedProviderId\": %d}".formatted(providerId)))
        .andExpect(status().isOk());

    byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J','F','I','F',0,1,1,0,0,1,0,1,0,0, (byte)0xFF, (byte)0xD9};
    org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
        "file", "trabajo.jpg", "image/jpeg", jpegBytes);

    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/public/providers/{pid}/leads/{lid}/photos", providerId, leadId)
            .file(file)
            .param("token", providerToken)
            .param("caption", "antes del trabajo"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("/uploads/lead-" + leadId + "/")))
        .andExpect(jsonPath("$.caption").value("antes del trabajo"));

    mockMvc.perform(get("/api/public/leads/{id}/photos", leadId)
            .param("token", leadToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].caption").value("antes del trabajo"));

    mockMvc.perform(get("/api/public/providers/{pid}/leads/{lid}/photos", providerId, leadId)
            .param("token", "wrong"))
        .andExpect(status().isForbidden());

    org.springframework.mock.web.MockMultipartFile bad = new org.springframework.mock.web.MockMultipartFile(
        "file", "doc.pdf", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46});
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/public/providers/{pid}/leads/{lid}/photos", providerId, leadId)
            .file(bad)
            .param("token", providerToken))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldLetCustomerUploadPhotoAndBeVisibleInPublicGet() throws Exception {
    MvcResult leadRes = mockMvc.perform(post("/api/public/leads")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {
                  "phone": "099778811",
                  "problem": "Necesito mandar foto de la perdida de agua",
                  "channel": "web-app",
                  "serviceCategory": "plomeria",
                  "zone": "Solymar"
                }
                """))
        .andExpect(status().isCreated())
        .andReturn();
    Integer leadId = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.id");
    String leadToken = JsonPath.read(leadRes.getResponse().getContentAsString(), "$.accessToken");

    byte[] jpegBytes = new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0x00, 0x10, 'J','F','I','F',0,1,1,0,0,1,0,1,0,0, (byte)0xFF, (byte)0xD9};
    org.springframework.mock.web.MockMultipartFile file = new org.springframework.mock.web.MockMultipartFile(
        "file", "perdida.jpg", "image/jpeg", jpegBytes);

    // Token invalido -> 403
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/public/leads/{id}/photos", leadId)
            .file(file)
            .param("token", "wrong"))
        .andExpect(status().isForbidden());

    // Upload de cliente OK
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/public/leads/{id}/photos", leadId)
            .file(file)
            .param("token", leadToken)
            .param("caption", "asi esta goteando"))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.url").value(org.hamcrest.Matchers.containsString("/uploads/lead-" + leadId + "/")))
        .andExpect(jsonPath("$.providerId").doesNotExist())
        .andExpect(jsonPath("$.caption").value("asi esta goteando"));

    // Visible en el GET publico
    mockMvc.perform(get("/api/public/leads/{id}/photos", leadId)
            .param("token", leadToken))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].caption").value("asi esta goteando"));

    // Tipo invalido -> 400
    org.springframework.mock.web.MockMultipartFile bad = new org.springframework.mock.web.MockMultipartFile(
        "file", "doc.pdf", "application/pdf", new byte[]{0x25, 0x50, 0x44, 0x46});
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/public/leads/{id}/photos", leadId)
            .file(bad)
            .param("token", leadToken))
        .andExpect(status().isBadRequest());

    // Limite de fotos por lead -> 400 (ya hay 1, completamos hasta 12 y la 13 debe fallar)
    for (int i = 0; i < 11; i++) {
      mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
              .multipart("/api/public/leads/{id}/photos", leadId)
              .file(new org.springframework.mock.web.MockMultipartFile(
                  "file", "foto" + i + ".jpg", "image/jpeg", jpegBytes))
              .param("token", leadToken))
          .andExpect(status().isCreated());
    }
    mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
            .multipart("/api/public/leads/{id}/photos", leadId)
            .file(new org.springframework.mock.web.MockMultipartFile(
                "file", "foto-de-mas.jpg", "image/jpeg", jpegBytes))
            .param("token", leadToken))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.error.message").value(org.hamcrest.Matchers.containsString("maximo")));
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
