package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.config.UploadsConfig;
import org.junit.jupiter.api.Test;

/**
 * Regresión del bug de prod 2026-08-07: con FIXY_UPLOADS_URL_PREFIX
 * absoluto ("https://api.fixy.com.uy/uploads") el handler de recursos se
 * registraba con la URL entera como patrón y nunca matcheaba — fotos y
 * notas de voz daban 404 en prod aunque el archivo existiera en disco.
 */
class UploadsConfigTest {

  @Test
  void prefijoRelativoQuedaIgual() {
    assertThat(UploadsConfig.handlerPathFor("/uploads")).isEqualTo("/uploads");
  }

  @Test
  void prefijoAbsolutoDeProdSeReduceAlPath() {
    assertThat(UploadsConfig.handlerPathFor("https://api.fixy.com.uy/uploads")).isEqualTo("/uploads");
  }

  @Test
  void prefijoAbsolutoConPathAnidadoConservaElPathCompleto() {
    assertThat(UploadsConfig.handlerPathFor("https://cdn.fixy.com.uy/static/uploads/")).isEqualTo("/static/uploads");
  }

  @Test
  void prefijoAbsolutoSinPathCaeAlDefault() {
    assertThat(UploadsConfig.handlerPathFor("https://api.fixy.com.uy")).isEqualTo("/uploads");
  }
}
