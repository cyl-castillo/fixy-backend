package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.model.Business;
import com.fixy.backend.model.BusinessStatus;
import com.fixy.backend.repository.BusinessRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link BusinessSlugService}: normalización, colisión, idempotencia y
 * no-regeneración (contrato del gap analysis 2026-08-25 §3, punto 2).
 */
@SpringBootTest
@Transactional
class BusinessSlugServiceTest {

  @Autowired private BusinessSlugService businessSlugService;
  @Autowired private BusinessRepository businessRepository;

  private Business persistBusiness(String name, String whatsapp) {
    Business business = new Business();
    business.setName(name);
    business.setWhatsappNumber(whatsapp);
    business.setCategory("otro");
    business.setStatus(BusinessStatus.ACTIVE);
    return businessRepository.save(business);
  }

  @Test
  void normalizaMinusculasSinTildesYNoAlfanumericoAGuiones() {
    Business business = persistBusiness("Ferretería Ñandú & Cía. \"La Costa\"", "098700001");

    String slug = businessSlugService.ensureSlug(business);

    assertThat(slug).isEqualTo("ferreteria-nandu-cia-la-costa");
  }

  @Test
  void colapsaGuionesConsecutivosYRecortaBordes() {
    Business business = persistBusiness("   ---Panadería---   del Barrio---", "098700002");

    String slug = businessSlugService.ensureSlug(business);

    assertThat(slug).doesNotContain("--");
    assertThat(slug).doesNotStartWith("-");
    assertThat(slug).doesNotEndWith("-");
  }

  @Test
  void esIdempotenteMismaLlamadaDosVecesDevuelveElMismoSlug() {
    Business business = persistBusiness("Comercio Idempotente Test", "098700003");

    String first = businessSlugService.ensureSlug(business);
    String second = businessSlugService.ensureSlug(business);

    assertThat(second).isEqualTo(first);
  }

  @Test
  void nuncaRegeneraUnSlugYaAsignadoAunqueElNombreCambieDespues() {
    Business business = persistBusiness("Comercio Original Nombre Test", "098700004");
    String originalSlug = businessSlugService.ensureSlug(business);

    business.setName("Comercio Con Nombre Completamente Distinto Test");
    businessRepository.save(business);

    String slugTrasRenombrar = businessSlugService.ensureSlug(business);

    assertThat(slugTrasRenombrar).isEqualTo(originalSlug);
  }

  @Test
  void colisionAgregaSufijoDeIdYQuedaUnico() {
    Business primero = persistBusiness("Comercio Colision Slug Test", "098700005");
    Business segundo = persistBusiness("Comercio Colision Slug Test", "098700006");

    String slugPrimero = businessSlugService.ensureSlug(primero);
    String slugSegundo = businessSlugService.ensureSlug(segundo);

    assertThat(slugPrimero).isEqualTo("comercio-colision-slug-test");
    assertThat(slugSegundo).isEqualTo("comercio-colision-slug-test-" + segundo.getId());
    assertThat(slugSegundo).isNotEqualTo(slugPrimero);
  }

  @Test
  void nombreSoloConSimbolosCaeAlFallbackComercio() {
    Business business = persistBusiness("★★★ !!! ···", "098700007");

    String slug = businessSlugService.ensureSlug(business);

    assertThat(slug).isEqualTo("comercio");
  }

  @Test
  void nombreLargoQuedaTruncadoAMaximo60Caracteres() {
    String nombreLargo = "Ferretería Y Pinturería Y Bazar Y Almacén De Ramos Generales Del Barrio Entero Test";
    Business business = persistBusiness(nombreLargo, "098700008");

    String slug = businessSlugService.ensureSlug(business);

    assertThat(slug.length()).isLessThanOrEqualTo(60);
    assertThat(slug).doesNotEndWith("-");
  }

  @Test
  void elSlugQuedaPersistidoEnLaEntidad() {
    Business business = persistBusiness("Comercio Persistencia Slug Test", "098700009");

    String slug = businessSlugService.ensureSlug(business);

    Business reloaded = businessRepository.findById(business.getId()).orElseThrow();
    assertThat(reloaded.getSlug()).isEqualTo(slug);
  }
}
