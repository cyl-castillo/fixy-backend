package com.fixy.backend.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

/**
 * {@code GET /sitemap.xml} — público (sin credenciales), content-type XML,
 * siempre incluye al menos home y /ofertas. El detalle de qué ofertas
 * entran vive en {@link com.fixy.backend.service.SitemapServiceTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
class SitemapControllerTest {

  @Autowired private MockMvc mockMvc;

  @Test
  void esPublicoYDevuelveXmlConHomeYOfertas() throws Exception {
    MvcResult res = mockMvc.perform(get("/sitemap.xml"))
        .andExpect(status().isOk())
        .andExpect(content().contentTypeCompatibleWith("application/xml"))
        .andReturn();

    String body = res.getResponse().getContentAsString();
    assertThat(body).startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>");
    assertThat(body).contains("<urlset xmlns=\"http://www.sitemaps.org/schemas/sitemap/0.9\">");
    assertThat(body).contains("<loc>https://www.fixy.com.uy/</loc>");
    assertThat(body).contains("<loc>https://www.fixy.com.uy/ofertas</loc>");
    assertThat(body).contains("<loc>https://www.fixy.com.uy/sumate</loc>");
  }
}
