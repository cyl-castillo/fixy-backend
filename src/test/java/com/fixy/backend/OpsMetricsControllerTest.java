package com.fixy.backend;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@AutoConfigureMockMvc
class OpsMetricsControllerTest {

  @Autowired
  private MockMvc mockMvc;

  @Test
  void shouldRejectUnauthenticatedRequests() throws Exception {
    mockMvc.perform(get("/api/ops/metrics/daily"))
        .andExpect(status().isUnauthorized());
  }

  @Test
  void shouldReturnDailyMetricsForAuthenticatedOps() throws Exception {
    mockMvc.perform(get("/api/ops/metrics/daily")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalLeadsCreated").exists())
        .andExpect(jsonPath("$.fillRatePercentage").exists())
        .andExpect(jsonPath("$.leadsByStatus").exists())
        .andExpect(jsonPath("$.leadsConsideredForResponseTime").exists())
        .andExpect(jsonPath("$.repeatRateAutodeclaredPercentage").exists());
  }

  @Test
  void shouldRejectInvalidDateRange() throws Exception {
    mockMvc.perform(get("/api/ops/metrics/daily")
            .param("from", "2026-06-10")
            .param("to", "2026-06-01")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shouldAcceptSimpleDateParams() throws Exception {
    mockMvc.perform(get("/api/ops/metrics/daily")
            .param("from", "2026-06-01")
            .param("to", "2026-06-08")
            .with(httpBasic("test-ops", "test-pass")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.from").exists())
        .andExpect(jsonPath("$.to").exists());
  }
}
