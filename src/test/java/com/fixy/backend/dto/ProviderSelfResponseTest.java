package com.fixy.backend.dto;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.model.ProviderVerificationStatus;
import java.util.List;
import org.junit.jupiter.api.Test;

/**
 * H1 del roadmap de enganche de proveedores: el panel del proveedor debe
 * poder mostrar su propia reputación. Cubre que {@code fromEntity} expone
 * rating con y sin calificaciones, sin forzar 0.0 cuando no hay ratings.
 */
class ProviderSelfResponseTest {

  private Provider baseProvider() {
    Provider provider = new Provider();
    provider.setId(1L);
    provider.setName("Juan Pérez");
    provider.setPhone("099123456");
    provider.setCategories("plomeria");
    provider.setStatus(ProviderStatus.AVAILABLE);
    provider.setVerificationStatus(ProviderVerificationStatus.VERIFIED);
    return provider;
  }

  @Test
  void includesRatingAverageAndCountWhenProviderHasRatings() {
    Provider provider = baseProvider();
    provider.setRatingAverage(4.8);
    provider.setRatingCount(12);

    ProviderSelfResponse response = ProviderSelfResponse.fromEntity(provider, List.of());

    assertThat(response.ratingAverage()).isEqualTo(4.8);
    assertThat(response.ratingCount()).isEqualTo(12);
  }

  @Test
  void doesNotForceZeroRatingWhenProviderHasNoRatingsYet() {
    Provider provider = baseProvider();
    provider.setRatingAverage(null);
    provider.setRatingCount(0);

    ProviderSelfResponse response = ProviderSelfResponse.fromEntity(provider, List.of());

    assertThat(response.ratingAverage()).isNull();
    assertThat(response.ratingCount()).isZero();
  }

  @Test
  void treatsNullRatingCountAsZeroWithoutForcingRatingAverage() {
    Provider provider = baseProvider();
    provider.setRatingAverage(null);
    provider.setRatingCount(null);

    ProviderSelfResponse response = ProviderSelfResponse.fromEntity(provider, List.of());

    assertThat(response.ratingAverage()).isNull();
    assertThat(response.ratingCount()).isZero();
  }

  @Test
  void treatsNullAcceptingWorkAsAvailableForLegacyProviders() {
    // Proveedores creados antes de este campo (ej. Melissa en prod) no
    // deben quedar "en pausa" por default solo porque el campo es null.
    Provider provider = baseProvider();
    provider.setAcceptingWork(null);

    ProviderSelfResponse response = ProviderSelfResponse.fromEntity(provider, List.of());

    assertThat(response.acceptingWork()).isTrue();
  }

  @Test
  void exposesAcceptingWorkFalseWhenProviderIsPaused() {
    Provider provider = baseProvider();
    provider.setAcceptingWork(false);

    ProviderSelfResponse response = ProviderSelfResponse.fromEntity(provider, List.of());

    assertThat(response.acceptingWork()).isFalse();
  }

  /**
   * El panel tiene que poder decirle al proveedor que una zona que declaró no
   * existe para Fixy — hasta hoy se guardaba, se mostraba y no matcheaba nunca
   * (caso del proveedor #16 en prod, 2026-08-07).
   */
  @Test
  void exposesZonesFixyDoesNotRecognize() {
    Provider provider = baseProvider();
    provider.setPrimaryZone("Solymar");
    provider.setCoverageZones("Lagomar, Pocitos");

    ProviderSelfResponse response = ProviderSelfResponse.fromEntity(provider, List.of());

    assertThat(response.unrecognizedZones()).containsExactly("Pocitos");
  }

  @Test
  void reportsNoUnrecognizedZonesForAProviderWithACleanCatalog() {
    Provider provider = baseProvider();
    provider.setPrimaryZone("La Costa");
    provider.setCoverageZones(null);

    ProviderSelfResponse response = ProviderSelfResponse.fromEntity(provider, List.of());

    assertThat(response.unrecognizedZones()).isEmpty();
  }
}
