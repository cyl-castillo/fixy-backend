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
        "Se rompio la ducha y pierde agua sin parar en Pocitos, Montevideo",
        "Lucia",
        "093551242",
        "whatsapp"
    );

    IntakeResponse response = service.classify(request);

    assertEquals("cliente", response.leadType());
    assertEquals("plomeria", response.serviceCategory());
    assertEquals("Pocitos", response.area());
    assertEquals("alta", response.urgency());
    assertTrue(response.missingFields().contains("direccion exacta"));
  }
}
