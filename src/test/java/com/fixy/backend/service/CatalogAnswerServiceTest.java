package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessCatalogItemConfidence;
import com.fixy.backend.model.BusinessCatalogItemKind;
import com.fixy.backend.repository.BusinessCatalogItemRepository;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

/**
 * Motor determinista de respuesta sobre el catálogo (Fase 2, gap analysis
 * 2026-08-25 §2) — la única dependencia externa ({@link
 * BusinessCatalogItemRepository}) se mockea, mismo patrón que {@link
 * OfferAnalysisServiceTest}: esto es matching puro, no hace falta Spring ni H2.
 */
@ExtendWith(MockitoExtension.class)
class CatalogAnswerServiceTest {

  private static final Long BUSINESS_ID = 1L;

  @Mock
  private BusinessCatalogItemRepository catalogItemRepository;

  private CatalogAnswerService service;

  private void service() {
    service = new CatalogAnswerService(catalogItemRepository);
  }

  private BusinessCatalogItem item(long id, String label, BusinessCatalogItemConfidence confidence, boolean available) {
    BusinessCatalogItem item = new BusinessCatalogItem();
    item.setId(id);
    item.setLabel(label);
    item.setKind(BusinessCatalogItemKind.PRODUCT);
    item.setConfidence(confidence);
    item.setAvailable(available);
    item.setActive(true);
    return item;
  }

  private void mockItems(List<BusinessCatalogItem> items) {
    when(catalogItemRepository.findByBusinessIdAndActiveTrue(BUSINESS_ID)).thenReturn(items);
  }

  // --- match de frase completa ---

  @Test
  void matchDeFraseCompletaConfirmadoDisponible_respondeAutoYes() {
    service();
    mockItems(List.of(item(1, "Pintura Sherwin Williams", BusinessCatalogItemConfidence.CONFIRMADO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "¿Tienen pintura sherwin williams?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_YES);
    assertThat(result.matchedItem().getId()).isEqualTo(1L);
  }

  @Test
  void declaradoDisponibleTambienRespondeAutoYes() {
    service();
    mockItems(List.of(item(1, "Cemento Portland", BusinessCatalogItemConfidence.DECLARADO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tienen cemento portland?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_YES);
  }

  @Test
  void confirmadoNoDisponible_respondeAutoNo() {
    service();
    mockItems(List.of(item(1, "Taladro Bosch", BusinessCatalogItemConfidence.CONFIRMADO, false)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tenes taladro bosch?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_NO);
    assertThat(result.matchedItem().getId()).isEqualTo(1L);
  }

  // --- tildes ---

  @Test
  void matchToleraTildesEnAmbasDirecciones_labelConTildePreguntaSinTilde() {
    service();
    mockItems(List.of(item(1, "Válvula", BusinessCatalogItemConfidence.CONFIRMADO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "necesito saber si tienen valvula");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_YES);
  }

  @Test
  void matchToleraTildesEnAmbasDirecciones_labelSinTildePreguntaConTilde() {
    service();
    mockItems(List.of(item(1, "Shangrila", BusinessCatalogItemConfidence.CONFIRMADO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "hacen envios a Shangrilá?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_YES);
  }

  // --- stopwords / relleno alrededor de los términos que importan ---

  @Test
  void ignoraStopwordsYRellenoAlrededorDeLosTerminosQueImportan() {
    service();
    mockItems(List.of(item(1, "Cemento Portland", BusinessCatalogItemConfidence.CONFIRMADO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(
        BUSINESS_ID, "Hola, buenas! De casualidad no sé si tienen por ahí un poco de cemento portland?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_YES);
  }

  @Test
  void matchPorSubconjuntoDeTokensDelLabelSinFraseContigua() {
    service();
    mockItems(List.of(item(1, "Pintura Sherwin Williams", BusinessCatalogItemConfidence.DECLARADO, true)));

    // orden distinto: no aparece como frase contigua, matchea por tokens del label.
    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tenes williams sherwin en pintura?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_YES);
  }

  @Test
  void noConfundeLabelCortoDentroDeOtraPalabra() {
    service();
    mockItems(List.of(item(1, "Pan", BusinessCatalogItemConfidence.CONFIRMADO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "¿Tienen empanadas?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.ESCALATE);
  }

  // --- prioridad cuando matchean varios ítems ---

  @Test
  void prioridadElNoConfirmadoGanaSobreElSiConfirmadoAunqueSeaMenosEspecifico() {
    service();
    mockItems(List.of(
        item(1, "taladro", BusinessCatalogItemConfidence.CONFIRMADO, false),
        item(2, "taladro Bosch GSB 13 Professional", BusinessCatalogItemConfidence.CONFIRMADO, true)
    ));

    CatalogAnswerService.AnswerResult result = service.answer(
        BUSINESS_ID, "tenes taladro bosch gsb 13 professional?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.AUTO_NO);
    assertThat(result.matchedItem().getId()).isEqualTo(1L);
  }

  @Test
  void prioridadConfirmadoGanaSobreDeclarado() {
    service();
    mockItems(List.of(
        item(1, "taladro", BusinessCatalogItemConfidence.DECLARADO, true),
        item(2, "taladro", BusinessCatalogItemConfidence.CONFIRMADO, true)
    ));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tenes taladro?");

    assertThat(result.matchedItem().getId()).isEqualTo(2L);
  }

  @Test
  void aIgualdadDeRangoGanaElLabelMasEspecifico() {
    service();
    mockItems(List.of(
        item(1, "pintura", BusinessCatalogItemConfidence.CONFIRMADO, true),
        item(2, "pintura sherwin williams", BusinessCatalogItemConfidence.CONFIRMADO, true)
    ));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tenes pintura sherwin williams?");

    assertThat(result.matchedItem().getId()).isEqualTo(2L);
  }

  // --- escalado ---

  @Test
  void soloInferidoMatchea_escalaConLaPistaDelItem() {
    service();
    mockItems(List.of(item(1, "Cerámica San Lorenzo", BusinessCatalogItemConfidence.INFERIDO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tenes ceramica san lorenzo?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.ESCALATE);
    assertThat(result.matchedItem()).isNotNull();
    assertThat(result.matchedItem().getId()).isEqualTo(1L);
  }

  @Test
  void sinNingunMatch_escalaSinPista() {
    service();
    mockItems(List.of(item(1, "Cemento Portland", BusinessCatalogItemConfidence.CONFIRMADO, true)));

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tienen sillas de jardín?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.ESCALATE);
    assertThat(result.matchedItem()).isNull();
  }

  @Test
  void catalogoVacio_escalaSinPista() {
    service();
    mockItems(List.of());

    CatalogAnswerService.AnswerResult result = service.answer(BUSINESS_ID, "tienen algo?");

    assertThat(result.kind()).isEqualTo(CatalogAnswerService.AnswerResult.Kind.ESCALATE);
  }

  // --- findMatch (usado por el upsert del dueño) ---

  @Test
  void findMatchDevuelveElItemAunqueSeaInferido() {
    service();
    mockItems(List.of(item(1, "Cerámica San Lorenzo", BusinessCatalogItemConfidence.INFERIDO, true)));

    Optional<BusinessCatalogItem> found = service.findMatch(BUSINESS_ID, "tenes ceramica san lorenzo?");

    assertThat(found).isPresent();
    assertThat(found.get().getId()).isEqualTo(1L);
  }

  @Test
  void findMatchVacioSiNoHayNingunMatch() {
    service();
    mockItems(List.of());

    assertThat(service.findMatch(BUSINESS_ID, "algo raro")).isEmpty();
  }

  // --- extractTerm ---

  @Test
  void extractTermSacaStopwordsYPuntuacionYCapitaliza() {
    service();

    String term = service.extractTerm("¿Tenés detergente para pisos?");

    assertThat(term).isEqualTo("Detergente pisos");
  }

  @Test
  void extractTermSinTokensUtilesDevuelveLaPreguntaTalCualSoloCapitalizada() {
    service();

    // "hola" y "buenas" son ambas stopwords: no queda ningún token útil, se
    // devuelve la pregunta original (con la primera letra en mayúscula).
    String term = service.extractTerm("hola buenas");

    assertThat(term).isEqualTo("Hola buenas");
  }

  @Test
  void extractTermTruncaA120() {
    service();
    String longQuestion = "detergente " + "x".repeat(200);

    String term = service.extractTerm(longQuestion);

    assertThat(term.length()).isEqualTo(120);
  }
}
