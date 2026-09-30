package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fixy.backend.domain.DomainCatalog;
import com.fixy.backend.domain.Playbook;
import com.fixy.backend.dto.McpToolDefinition;
import jakarta.validation.Validator;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

/**
 * Core Fase 2: McpToolService verifica que las tools que expone están declaradas en el playbook
 * ({@code tools}); una tool expuesta que el playbook no declara hace fallar el arranque.
 */
class McpToolPlaybookTest {

  private static McpToolService serviceWith(Playbook playbook) {
    return new McpToolService(new ObjectMapper(), mock(ServiceCatalogService.class),
        mock(ProviderCatalogService.class), mock(OrderService.class), mock(Validator.class),
        "https://www.fixy.com.uy", playbook);
  }

  @Test
  void lasToolsExpuestasEstanDeclaradasEnElPlaybook() {
    McpToolService service = serviceWith(Playbook.get());

    assertThat(service.definitions()).extracting(McpToolDefinition::name)
        .containsExactlyInAnyOrderElementsOf(Playbook.get().tools());
    assertThat(Playbook.get().tools())
        .contains(McpToolService.LIST_SERVICES_TOOL, McpToolService.CREATE_ORDER_TOOL);
  }

  @Test
  void unaToolExpuestaQueElPlaybookNoDeclaraHaceFallarElArranque() throws IOException {
    String yaml;
    try (var in = McpToolPlaybookTest.class.getResourceAsStream("/domain/home-services-playbook.yml")) {
      yaml = new String(in.readAllBytes(), StandardCharsets.UTF_8);
    }
    Playbook sinCrearPedido = Playbook.parse(
        new ByteArrayInputStream(yaml.replace("tools: [fixy_list_services, fixy_create_order]",
            "tools: [fixy_list_services]").getBytes(StandardCharsets.UTF_8)),
        "test-playbook.yml", DomainCatalog.get());

    assertThatThrownBy(() -> serviceWith(sinCrearPedido))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("fixy_create_order")
        .hasMessageContaining("el playbook no declara");
  }
}
