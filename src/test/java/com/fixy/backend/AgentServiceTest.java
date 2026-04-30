package com.fixy.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.dto.IntakeRequest;
import com.fixy.backend.dto.IntakeResponse;
import com.fixy.backend.service.AgentService;
import org.junit.jupiter.api.Test;

class AgentServiceTest {

  @Test
  void shouldClassifyCustomerPlumbingCase() {
    AgentService service = new AgentService(new ObjectMapper(), "", "gpt-4.1-mini");
    IntakeRequest request = new IntakeRequest(
        "Se rompio la ducha y pierde agua sin parar en Solymar, Ciudad de la Costa",
        "Lucia",
        "093551242",
        "whatsapp"
    );

    IntakeResponse response = service.classify(request);

    assertEquals("cliente", response.leadType());
    assertEquals("plomeria", response.serviceCategory());
    assertEquals("Solymar", response.area());
    assertEquals("alta", response.urgency());
    assertTrue(response.missingFields().contains("direccion exacta"));
  }

  @Test
  void shouldClassifyAirConditioningCase() {
    AgentService service = new AgentService(new ObjectMapper(), "", "gpt-4.1-mini");
    IntakeRequest request = new IntakeRequest(
        "El aire acondicionado split no enfria en Lagomar",
        "Carlos",
        "099111222",
        "web-app"
    );

    IntakeResponse response = service.classify(request);

    assertEquals("cliente", response.leadType());
    assertEquals("aires_acondicionados", response.serviceCategory());
    assertEquals("Lagomar", response.area());
  }

  @Test
  void shouldUseStructuredIntakeFieldsWhenProvided() {
    AgentService service = new AgentService(new ObjectMapper(), "", "gpt-4.1-mini");
    IntakeRequest request = new IntakeRequest(
        "Necesito ayuda, se rompió algo abajo de la pileta.",
        "Carlos",
        "099111222",
        "web-app",
        "plomeria",
        "Solymar",
        "hoy",
        "Av. Principal y calle 3",
        "Pierde agua abajo de la pileta"
    );

    IntakeResponse response = service.classify(request);

    assertEquals("cliente", response.leadType());
    assertEquals("plomeria", response.serviceCategory());
    assertEquals("Solymar", response.area());
    assertEquals("media", response.urgency());
    assertTrue(response.missingFields().contains("foto del problema"));
    assertTrue(!response.missingFields().contains("zona"));
    assertTrue(!response.missingFields().contains("direccion exacta"));
  }
}
