package com.fixy.backend.config;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import com.fixy.backend.repository.ProviderRepository;
import java.util.List;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Seed de proveedores ficticios para desarrollo local. Refundación de Fixy,
 * fase 1 (contrato §7): "nunca más en prod" — los 6 proveedores hardcodeados
 * acá salieron reales en prod y hubo que pedirle a Carlos un DELETE manual
 * (ver {@code deploy/aws/} para el comando preparado). Por eso ahora corre
 * SOLO con {@code fixy.seed.providers=true} explícito, default false en
 * todos lados — dev local tiene que prenderlo a mano, y prod nunca lo
 * setea.
 */
@Configuration
public class ProviderSeedConfig {

  @Bean
  CommandLineRunner seedProviders(
      ProviderRepository providerRepository,
      @Value("${fixy.seed.providers:false}") boolean seedEnabled
  ) {
    return args -> {
      if (!seedEnabled || providerRepository.count() > 0) {
        return;
      }

      providerRepository.saveAll(List.of(
          provider(
              "Plomería Solymar",
              "099100101",
              "099100101",
              "plomeria",
              "Solymar",
              "Solymar, Lagomar, El Pinar",
              "Ciudad de la Costa",
              "Canelones",
              "existing_network",
              ProviderStatus.AVAILABLE,
              ProviderVerificationStatus.BASIC_CHECKED,
              "Proveedor seed para pruebas iniciales de plomería"
          ),
          provider(
              "Barométrica Costa Norte",
              "099100202",
              "099100202",
              "barometrica",
              "El Pinar",
              "El Pinar, Solymar, Parque del Plata",
              "Ciudad de la Costa",
              "Canelones",
              "existing_network",
              ProviderStatus.AVAILABLE,
              ProviderVerificationStatus.BASIC_CHECKED,
              "Proveedor seed para pruebas iniciales de barométrica"
          ),
          provider(
              "Jardines del Este",
              "099100303",
              "099100303",
              "jardineria",
              "Lagomar",
              "Lagomar, Solymar, Shangrilá",
              "Ciudad de la Costa",
              "Canelones",
              "existing_network",
              ProviderStatus.AVAILABLE,
              ProviderVerificationStatus.UNVERIFIED,
              "Proveedor seed para pruebas iniciales de jardinería"
          ),
          provider(
              "Plomería Atlántida Express",
              "099100404",
              "099100404",
              "plomeria",
              "Atlántida",
              "Atlántida, Parque del Plata, El Pinar",
              "Costa de Oro",
              "Canelones",
              "web_discovered",
              ProviderStatus.NEW,
              ProviderVerificationStatus.UNVERIFIED,
              "Proveedor seed simulando descubrimiento web"
          ),
          provider(
              "Servicios Costa Hogar",
              "099100505",
              "099100505",
              "plomeria, jardineria",
              "Ciudad de la Costa",
              "Solymar, Lagomar, El Pinar, Shangrilá",
              "Ciudad de la Costa",
              "Canelones",
              "manual",
              ProviderStatus.AVAILABLE,
              ProviderVerificationStatus.BASIC_CHECKED,
              "Proveedor multipropósito para pruebas de matching"
          ),
          provider(
              "Aires Costa",
              "099100606",
              "099100606",
              "aires_acondicionados",
              "Ciudad de la Costa",
              "Solymar, Lagomar, El Pinar, Shangrilá, Barra de Carrasco",
              "Ciudad de la Costa",
              "Canelones",
              "manual",
              ProviderStatus.AVAILABLE,
              ProviderVerificationStatus.UNVERIFIED,
              "Proveedor seed para pruebas iniciales de aires acondicionados; reemplazar por proveedor real de Carlos"
          )
      ));
    };
  }

  private Provider provider(
      String name,
      String phone,
      String whatsappNumber,
      String categories,
      String primaryZone,
      String coverageZones,
      String city,
      String department,
      String sourceType,
      ProviderStatus status,
      ProviderVerificationStatus verificationStatus,
      String notes
  ) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone(phone);
    provider.setWhatsappNumber(whatsappNumber);
    provider.setCategories(categories);
    provider.setPrimaryZone(primaryZone);
    provider.setCoverageZones(coverageZones);
    provider.setCity(city);
    provider.setDepartment(department);
    provider.setSourceType(sourceType);
    provider.setStatus(status);
    provider.setVerificationStatus(verificationStatus);
    provider.setNotes(notes);
    return provider;
  }
}
