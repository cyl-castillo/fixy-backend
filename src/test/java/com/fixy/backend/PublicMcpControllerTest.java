package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.ServiceCatalogItem;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ServiceCatalogItemRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

/**
 * Servidor MCP mínimo, stateless (Refundación fase 1, contrato §8):
 * {@code POST /api/public/mcp}, JSON-RPC 2.0 sin SSE.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
class PublicMcpControllerTest {

  @Autowired private MockMvc mockMvc;
  @Autowired private LeadRepository leadRepository;
  @Autowired private ServiceCatalogItemRepository serviceCatalogItemRepository;

  @Test
  void initializeDevuelveProtocolVersionYServerInfo() throws Exception {
    mockMvc.perform(post("/api/public/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc":"2.0","id":1,"method":"initialize","params":{}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.jsonrpc").value("2.0"))
        .andExpect(jsonPath("$.id").value(1))
        .andExpect(jsonPath("$.result.protocolVersion").exists())
        .andExpect(jsonPath("$.result.serverInfo.name").value("fixy"));
  }

  @Test
  void notificationsInitialized_devuelve204SinBody() throws Exception {
    mockMvc.perform(post("/api/public/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc":"2.0","method":"notifications/initialized"}
                """))
        .andExpect(status().isNoContent());
  }

  @Test
  void ping_devuelveResultadoVacio() throws Exception {
    mockMvc.perform(post("/api/public/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc":"2.0","id":"p1","method":"ping"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value("p1"))
        .andExpect(jsonPath("$.result").exists());
  }

  @Test
  void toolsList_devuelveLasDosToolsConInputSchema() throws Exception {
    mockMvc.perform(post("/api/public/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc":"2.0","id":2,"method":"tools/list"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.tools.length()").value(2))
        .andExpect(jsonPath("$.result.tools[?(@.name=='fixy_list_services')].inputSchema").exists())
        .andExpect(jsonPath("$.result.tools[?(@.name=='fixy_create_order')].inputSchema").exists());
  }

  @Test
  void toolsCallFixyListServices_devuelveContenidoDeTexto() throws Exception {
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory("plomeria");
    item.setCode("svc-mcp-list-test");
    item.setName("Visita de plomería MCP");
    item.setPriceFrom(700);
    item.setActive(true);
    serviceCatalogItemRepository.save(item);

    mockMvc.perform(post("/api/public/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc":"2.0","id":3,"method":"tools/call","params":{"name":"fixy_list_services","arguments":{"category":"plomeria"}}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").value(false))
        .andExpect(jsonPath("$.result.content[0].type").value("text"))
        .andExpect(jsonPath("$.result.content[0].text").value(org.hamcrest.Matchers.containsString("Visita de plomería MCP")));
  }

  @Test
  void toolsCallFixyCreateOrder_creaUnLeadIgualQueElEndpointRest() throws Exception {
    ServiceCatalogItem item = new ServiceCatalogItem();
    item.setCategory("aires_acondicionados");
    item.setCode("svc-mcp-order-test");
    item.setName("Service de split MCP");
    item.setPriceFrom(2900);
    item.setActive(true);
    serviceCatalogItemRepository.save(item);

    long before = leadRepository.count();

    MvcResult result = mockMvc.perform(post("/api/public/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc":"2.0","id":4,"method":"tools/call","params":{"name":"fixy_create_order",
                  "arguments":{"serviceCode":"svc-mcp-order-test","zone":"Lagomar","timeWindow":"hoy",
                    "name":"Diego","phone":"099444555"}}}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.result.isError").value(false))
        .andReturn();

    assertThat(leadRepository.count()).isEqualTo(before + 1);

    String body = result.getResponse().getContentAsString();
    String secondBlockJson = com.jayway.jsonpath.JsonPath.read(body, "$.result.content[1].text");
    Long leadId = ((Number) com.jayway.jsonpath.JsonPath.read(secondBlockJson, "$.leadId")).longValue();
    Lead lead = leadRepository.findById(leadId).orElseThrow();
    assertThat(lead.getServiceCode()).isEqualTo("svc-mcp-order-test");
    assertThat(lead.getChannel()).isEqualTo("web-order");
  }

  @Test
  void metodoDesconocido_devuelveErrorJsonRpc() throws Exception {
    mockMvc.perform(post("/api/public/mcp")
            .contentType(MediaType.APPLICATION_JSON)
            .content("""
                {"jsonrpc":"2.0","id":5,"method":"no/existe"}
                """))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.error.code").value(-32601));
  }
}
