package com.fixy.backend.service;

import com.fixy.backend.model.BusinessCatalogItem;
import com.fixy.backend.model.BusinessCatalogItemConfidence;
import com.fixy.backend.model.CoverageZone;
import com.fixy.backend.repository.BusinessCatalogItemRepository;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import org.springframework.stereotype.Service;

/**
 * Motor de respuesta determinista sobre el catálogo estructurado de la ficha
 * (Fase 2 del gap analysis 2026-08-25 §2) — doctrina "todo en código, cero
 * LLM" (ver {@code OfferAnalysisService.java:20}): "¿tenés X?" se resuelve
 * contra {@link BusinessCatalogItem} ACTIVOS de un comercio sin ningún
 * modelo de lenguaje de por medio, para que la respuesta sea siempre la
 * misma ante la misma pregunta.
 *
 * <h2>Normalización</h2>
 * Reusa {@link CoverageZone#normalize(String)} (minúsculas + sin acentos) —
 * la misma regla que ya usa la jerarquía de zonas para que "Shangrilá" y
 * "shangrila" sean el mismo token, acá aplicada a preguntas y labels.
 *
 * <h2>Matching</h2>
 * Un ítem matchea la pregunta si su label (o sus notes) normalizado aparece
 * como frase completa dentro de la pregunta — con límites de palabra, para
 * que "pan" no matchee dentro de "empanada" — o si TODOS los tokens del
 * label están presentes entre los tokens no-stopword de la pregunta.
 *
 * <h2>Prioridad cuando matchean varios ítems</h2>
 * {@code CONFIRMADO} con {@code available=false} &gt; {@code CONFIRMADO}
 * (disponible) &gt; {@code DECLARADO} &gt; {@code INFERIDO} — el "no"
 * confirmado gana siempre para no mentirle al vecino. A igualdad de rango,
 * gana el label más específico (más largo) y, si sigue empatado, el ítem
 * más antiguo (id ascendente) para que el resultado sea estable.
 *
 * <h2>Decisión</h2>
 * Si el ítem ganador es {@code CONFIRMADO} o {@code DECLARADO} la respuesta
 * es automática ({@code available} decide sí/no). Si el ganador es {@code
 * INFERIDO}, o no matchea nada, se escala al dueño — con el ítem {@code
 * INFERIDO} como pista si lo hay (ver {@link AnswerResult}).
 */
@Service
public class CatalogAnswerService {

  /** Stopwords mínimas, YA normalizadas (sin acentos) — la comparación
   * siempre es contra tokens normalizados, así que no hace falta duplicar
   * cada entrada con y sin tilde. */
  private static final Set<String> STOPWORDS = Set.of(
      "tenes", "tiene", "tienen", "hay", "precio", "cuanto",
      "de", "del", "el", "la", "los", "las", "un", "una", "unos", "unas",
      "en", "con", "por", "para", "y", "o", "a", "que", "es", "esta",
      "ese", "esa", "esos", "esas", "este", "estos", "estas",
      "sabes", "sabe", "me", "te", "se", "mi", "tu", "su",
      "vende", "venden", "consigo", "queria", "quiero",
      "hola", "buenas", "buenos", "che", "porfa", "porfavor"
  );

  private static final Pattern TOKEN_SPLIT = Pattern.compile("[^a-z0-9]+");
  private static final int TERM_MAX_LENGTH = 120;

  private final BusinessCatalogItemRepository catalogItemRepository;

  public CatalogAnswerService(BusinessCatalogItemRepository catalogItemRepository) {
    this.catalogItemRepository = catalogItemRepository;
  }

  /** La decisión del motor: respuesta automática o escalar (ver javadoc de la clase). */
  public AnswerResult answer(Long businessId, String question) {
    Optional<BusinessCatalogItem> best = bestMatch(businessId, question);
    if (best.isEmpty()) {
      return AnswerResult.escalate(null);
    }
    BusinessCatalogItem item = best.get();
    if (item.getConfidence() == BusinessCatalogItemConfidence.INFERIDO) {
      return AnswerResult.escalate(item);
    }
    return item.isAvailable() ? AnswerResult.autoYes(item) : AnswerResult.autoNo(item);
  }

  /** El mismo matching que {@link #answer}, sin filtrar por confianza —
   * usado por {@code BusinessInquiryService.answerAsOwner} para decidir si
   * la respuesta del dueño actualiza un ítem existente o crea uno nuevo. */
  public Optional<BusinessCatalogItem> findMatch(Long businessId, String question) {
    return bestMatch(businessId, question);
  }

  /** La pregunta sin stopwords, sin puntuación y con la primera letra en
   * mayúscula — label de fallback cuando el dueño responde algo que el
   * catálogo todavía no tenía (máx 120, columna {@code
   * business_catalog_items.label}). Si no queda ningún token útil (pregunta
   * hecha solo de stopwords), devuelve la pregunta tal cual. */
  public String extractTerm(String question) {
    String raw = question == null ? "" : question.trim();
    List<String> kept = Arrays.stream(raw.split("\\s+"))
        .map(word -> word.replaceAll("[^\\p{L}\\p{N}]+", ""))
        .filter(word -> !word.isBlank())
        .filter(word -> !STOPWORDS.contains(normalize(word)))
        .toList();
    String term = String.join(" ", kept).trim();
    if (term.isBlank()) {
      term = raw;
    }
    term = capitalize(term);
    return term.length() > TERM_MAX_LENGTH ? term.substring(0, TERM_MAX_LENGTH).trim() : term;
  }

  private Optional<BusinessCatalogItem> bestMatch(Long businessId, String question) {
    if (question == null || question.isBlank()) {
      return Optional.empty();
    }
    List<BusinessCatalogItem> items = catalogItemRepository.findByBusinessIdAndActiveTrue(businessId);
    String normalizedQuestion = normalize(question);
    Set<String> questionTokens = meaningfulTokens(normalizedQuestion);

    return items.stream()
        .filter(item -> matchesItem(normalizedQuestion, questionTokens, item))
        .min(Comparator
            .comparingInt(this::rank)
            .thenComparing((BusinessCatalogItem item) -> normalize(item.getLabel()).length(), Comparator.reverseOrder())
            .thenComparing(BusinessCatalogItem::getId, Comparator.nullsLast(Comparator.naturalOrder())));
  }

  /** Menor = mejor. available=false CONFIRMADO gana siempre (el "no" real nunca se pisa). */
  private int rank(BusinessCatalogItem item) {
    if (item.getConfidence() == BusinessCatalogItemConfidence.CONFIRMADO) {
      return item.isAvailable() ? 1 : 0;
    }
    if (item.getConfidence() == BusinessCatalogItemConfidence.DECLARADO) {
      return 2;
    }
    return 3; // INFERIDO
  }

  private boolean matchesItem(String normalizedQuestion, Set<String> questionTokens, BusinessCatalogItem item) {
    return matchesText(normalizedQuestion, questionTokens, item.getLabel())
        || matchesText(normalizedQuestion, questionTokens, item.getNotes());
  }

  private boolean matchesText(String normalizedQuestion, Set<String> questionTokens, String text) {
    if (text == null || text.isBlank()) {
      return false;
    }
    String normalizedText = normalize(text);
    if (normalizedText.isBlank()) {
      return false;
    }
    Pattern wholePhrase = Pattern.compile("(?<![a-z0-9])" + Pattern.quote(normalizedText) + "(?![a-z0-9])");
    if (wholePhrase.matcher(normalizedQuestion).find()) {
      return true;
    }
    Set<String> textTokens = tokenize(normalizedText);
    return !textTokens.isEmpty() && questionTokens.containsAll(textTokens);
  }

  private Set<String> meaningfulTokens(String normalizedText) {
    Set<String> tokens = new LinkedHashSet<>();
    for (String token : tokenize(normalizedText)) {
      if (!STOPWORDS.contains(token)) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private Set<String> tokenize(String normalizedText) {
    Set<String> tokens = new LinkedHashSet<>();
    for (String token : TOKEN_SPLIT.split(normalizedText)) {
      if (!token.isBlank()) {
        tokens.add(token);
      }
    }
    return tokens;
  }

  private String normalize(String value) {
    return CoverageZone.normalize(value);
  }

  private String capitalize(String value) {
    if (value.isBlank()) {
      return value;
    }
    return Character.toUpperCase(value.charAt(0)) + value.substring(1);
  }

  /** Resultado tipado del motor — {@link #matchedItem} viaja siempre que hay
   * algún ítem involucrado (ganador en AUTO_*, pista INFERIDO en ESCALATE),
   * null solo cuando no matcheó absolutamente nada. */
  public record AnswerResult(Kind kind, BusinessCatalogItem matchedItem) {

    public enum Kind { AUTO_YES, AUTO_NO, ESCALATE }

    public static AnswerResult autoYes(BusinessCatalogItem item) {
      return new AnswerResult(Kind.AUTO_YES, item);
    }

    public static AnswerResult autoNo(BusinessCatalogItem item) {
      return new AnswerResult(Kind.AUTO_NO, item);
    }

    public static AnswerResult escalate(BusinessCatalogItem item) {
      return new AnswerResult(Kind.ESCALATE, item);
    }
  }
}
