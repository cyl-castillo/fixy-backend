package com.fixy.backend.config;

import com.fixy.backend.domain.DomainCatalog;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Expone como beans las piezas de dominio de Core Fase 2 (ver CORE_FASE2_CONTRATO.md)
 * para inyección. Son las MISMAS instancias que devuelven los accesos estáticos
 * ({@code DomainCatalog.get()}): los enums fachada y los estáticos de las políticas
 * no pueden recibir inyección, y no puede haber dos catálogos distintos en un proceso.
 */
@Configuration
public class DomainConfig {

  @Bean
  public DomainCatalog domainCatalog() {
    return DomainCatalog.get();
  }
}
