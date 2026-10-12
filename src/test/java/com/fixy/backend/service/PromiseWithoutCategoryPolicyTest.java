package com.fixy.backend.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.core.domain.DomainCatalog;
import com.fixy.backend.domain.fixy.HomeServicesPolicy;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Regla "promesa sin categoría" (lead #276), parte pura: qué frases cuentan como "ya lo estoy
 * gestionando" y qué texto sale en la pregunta de rubro. El loop completo se prueba en
 * {@link LeadAgentPromiseWithoutCategoryTest}.
 */
class PromiseWithoutCategoryPolicyTest {

  @ParameterizedTest
  @ValueSource(strings = {
      "Perfecto, lo gestiono ahora y te aviso en cuanto alguien tome el pedido",
      "Gestiono ahora tu pedido.",
      "Estoy buscando a alguien para vos en Lomas de Solymar.",
      "Sigo buscando un proveedor, no te preocupes.",
      "Ya lo estamos buscando, quedate tranquilo.",
      "Dale. Te aviso apenas un proveedor acepte tu pedido.",
      "Estoy contactando a un técnico de tu zona.",
      "Listo, lo estoy gestionando. Te avisamos cuando alguien confirme.",
  })
  void claimsHandling_detectaLasPromesasDeGestion(String reply) {
    assertThat(HomeServicesPolicy.claimsHandling(reply)).as(reply).isTrue();
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "¿En qué zona estás?",
      "Para buscarte a alguien necesito saber el rubro. ¿Es plomería o mandados?",
      "Contame un poco más: ¿qué necesitás arreglar en tu casa?",
      "Gracias por escribir, ¿cuál es tu barrio?",
      "",
  })
  void claimsHandling_noDisparaEnPreguntasNiAclaraciones(String reply) {
    assertThat(HomeServicesPolicy.claimsHandling(reply)).as(reply).isFalse();
  }

  @Test
  void claimsHandling_nullEsFalso() {
    assertThat(HomeServicesPolicy.claimsHandling(null)).isFalse();
  }

  @Test
  void askCategoryReply_sinDescartada_listaTodasLasMvpEnOrdenDelCatalogo() {
    String reply = HomeServicesPolicy.askCategoryReply(null);

    assertThat(reply).startsWith("Para buscarte a alguien necesito saber el rubro. ¿Es ").endsWith("?");
    assertThat(reply).doesNotContain("Me parece");
    assertLabelsInCatalogOrder(reply);
    // La última se une con "o": "..., a o b".
    List<String> labels = DomainCatalog.get().mvpLabels();
    assertThat(reply).contains(" o " + labels.get(labels.size() - 1) + "?");
  }

  @Test
  void askCategoryReply_conDescartada_laProponePrimeroYSigueListandoTodas() {
    String reply = HomeServicesPolicy.askCategoryReply("mandados");
    String label = DomainCatalog.get().humanLabel("mandados");

    assertThat(reply).startsWith("Me parece que es *" + label + "*; si es así, respondé «" + label + "». Si no, decime si es ");
    assertLabelsInCatalogOrder(reply);
  }

  @Test
  void askCategoryReply_descartadaNoMvpODesconocida_seTrataComoSinPropuesta() {
    assertThat(HomeServicesPolicy.askCategoryReply("electricidad")).doesNotContain("Me parece");
    assertThat(HomeServicesPolicy.askCategoryReply("otro")).doesNotContain("Me parece");
    assertThat(HomeServicesPolicy.askCategoryReply("inventada")).doesNotContain("Me parece");
  }

  /** La palabra que se le pide al vecino tiene que entrar por el clasificador: si no, la pregunta no
   * se cierra nunca. Vale para cada MVP (si alguien renombra una etiqueta, este test avisa). */
  @Test
  void askCategoryReply_laEtiquetaDeCadaMvpSeClasificaALaMismaCategoria() {
    for (var category : DomainCatalog.get().mvpCategories()) {
      assertThat(HomeServicesPolicy.detectCategoryFromMessages(List.of(category.label())))
          .as("etiqueta '%s'", category.label())
          .isEqualTo(category.id());
    }
  }

  private static void assertLabelsInCatalogOrder(String reply) {
    // Se mira solo la lista de opciones (la propuesta de adelante repite una etiqueta).
    int from = Math.max(reply.indexOf("decime si es "), reply.indexOf("¿Es "));
    assertThat(from).isGreaterThanOrEqualTo(0);
    for (String label : DomainCatalog.get().mvpLabels()) {
      int at = reply.indexOf(label, from);
      assertThat(at).as("etiqueta '%s' en orden del catálogo", label).isGreaterThanOrEqualTo(from);
      from = at + label.length();
    }
  }
}
