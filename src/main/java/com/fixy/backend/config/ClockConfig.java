package com.fixy.backend.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Clock del sistema como bean inyectable, para que servicios con lógica
 * de tiempo (ej. {@link com.fixy.backend.service.LeadClosingScheduler})
 * sean testeables con un Clock fijo/desplazado en vez de OffsetDateTime.now().
 */
@Configuration
public class ClockConfig {

  @Bean
  public Clock systemClock() {
    return Clock.systemDefaultZone();
  }
}
