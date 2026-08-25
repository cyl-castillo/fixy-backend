package com.fixy.backend.service;

import com.fixy.backend.dto.OfferAnalysis;
import com.fixy.backend.model.Offer;
import com.fixy.backend.model.OfferStatus;
import com.fixy.backend.repository.OfferRepository;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Análisis determinista "¿esta oferta conviene?" (fase 3, roadmap Ofertas —
 * ver {@code OfferAnalysis} para el contrato de cada campo). Lógica PURA
 * salvo el único acceso a datos ({@link OfferRepository}) necesario para
 * mirar el historial más allá del listado que está por responderse — regla
 * de oro del proyecto: nada de esto se le puede pedir al LLM 8B de prod por
 * prompt, todo va en código.
 *
 * <p>Costo: {@link #analyze} recibe el batch de ofertas VIGENTES a analizar
 * (ya filtradas por {@code OfferService.listPublic}, o una lista de un solo
 * elemento desde {@code getPublic}) y hace UNA query
 * ({@link OfferRepository#findByCategoryInAndStatusIn}) que trae TODO el
 * historial (ACTIVE + EXPIRED) de las categorías presentes en el batch —
 * nunca una query por oferta.
 */
@Service
public class OfferAnalysisService {

  static final int FIRST_TIME_MIN_WEEKS = 1;
  static final int FIRST_TIME_MAX_WEEKS = 12;

  /** Únicos estados que representan una oferta que alguna vez estuvo públicamente vigente. */
  private static final List<OfferStatus> HISTORY_STATUSES = List.of(OfferStatus.ACTIVE, OfferStatus.EXPIRED);

  private final OfferRepository offerRepository;
  private final DiscountParser discountParser;

  public OfferAnalysisService(OfferRepository offerRepository, DiscountParser discountParser) {
    this.offerRepository = offerRepository;
    this.discountParser = discountParser;
  }

  /**
   * Analiza cada oferta de {@code offers} (se asume ya vigente: {@code
   * ACTIVE} + {@code validUntil} futura, mismo filtro que {@code
   * OfferService.listPublic}). Devuelve un mapa {@code offerId -> analysis}
   * — nunca falta una entrada por cada oferta de entrada con id no nulo.
   *
   * <p>{@code bestOfCategory} se calcula contra TODAS las vigentes de la
   * categoría en el catálogo completo, no solo las del batch de entrada: si
   * el caller ya filtró por zona, "la mejor de la categoría" sigue siendo un
   * dato global del barrio, no relativo a la zona de quien mira en ese
   * momento — evita que el badge aparezca/desaparezca según el filtro.
   */
  public Map<Long, OfferAnalysis> analyze(List<Offer> offers, OffsetDateTime now) {
    if (offers.isEmpty()) {
      return Map.of();
    }

    List<String> categories = offers.stream().map(Offer::getCategory).distinct().toList();
    List<Offer> history = offerRepository.findByCategoryInAndStatusIn(categories, HISTORY_STATUSES);

    // Manual (no Collectors.toMap): discountPercent es Integer nullable y
    // toMap explota con NullPointerException apenas un valor es null — el
    // caso más común de todos (texto no comparable).
    Map<Long, Integer> percentByOfferId = new HashMap<>();
    for (Offer historical : history) {
      percentByOfferId.put(historical.getId(), discountParser.discountPercent(historical.getDiscountText()));
    }

    Map<String, List<Offer>> vigentesByCategory = history.stream()
        .filter(o -> isVigente(o, now))
        .collect(Collectors.groupingBy(Offer::getCategory));
    Map<String, Long> bestIdByCategory = computeBestOfCategory(vigentesByCategory, percentByOfferId);

    Map<Long, OfferAnalysis> result = new HashMap<>();
    for (Offer offer : offers) {
      Integer discountPercent = percentByOfferId.get(offer.getId());
      boolean best = offer.getId() != null && offer.getId().equals(bestIdByCategory.get(offer.getCategory()));
      Integer firstTimeInWeeks = firstTimeInWeeks(offer, discountPercent, history, percentByOfferId);
      result.put(offer.getId(), new OfferAnalysis(discountPercent, best, firstTimeInWeeks));
    }
    return result;
  }

  private boolean isVigente(Offer offer, OffsetDateTime now) {
    return offer.getStatus() == OfferStatus.ACTIVE
        && offer.getValidUntil() != null
        && offer.getValidUntil().isAfter(now);
  }

  /**
   * Una oferta por categoría como mucho: la de mayor {@code discountPercent}
   * entre las vigentes, empate a favor de la más vieja por {@code
   * createdAt}. Categorías con menos de 2 vigentes, o donde ninguna tiene
   * {@code discountPercent}, no entran al mapa (sin ganador).
   */
  private Map<String, Long> computeBestOfCategory(
      Map<String, List<Offer>> vigentesByCategory, Map<Long, Integer> percentByOfferId) {
    Map<String, Long> bestIdByCategory = new HashMap<>();
    for (Map.Entry<String, List<Offer>> entry : vigentesByCategory.entrySet()) {
      List<Offer> vigentes = entry.getValue();
      if (vigentes.size() < 2) {
        continue; // "mejor de 1" no dice nada.
      }
      Offer winner = null;
      for (Offer candidate : vigentes) {
        Integer percent = percentByOfferId.get(candidate.getId());
        if (percent == null) {
          continue; // no comparable, no puede ganar.
        }
        if (winner == null) {
          winner = candidate;
          continue;
        }
        int winnerPercent = percentByOfferId.get(winner.getId());
        if (percent > winnerPercent
            || (percent.equals(winnerPercent) && candidate.getCreatedAt().isBefore(winner.getCreatedAt()))) {
          winner = candidate;
        }
      }
      if (winner != null) {
        bestIdByCategory.put(entry.getKey(), winner.getId());
      }
    }
    return bestIdByCategory;
  }

  /**
   * Semanas completas desde la última vez (antes de {@code offer.createdAt})
   * que existió OTRA oferta de la misma categoría con {@code
   * discountPercent} igual o mejor. {@code null} si {@code discountPercent}
   * es null, si no hay antecedente, o si el más reciente es de hace menos de
   * una semana.
   */
  private Integer firstTimeInWeeks(
      Offer offer, Integer discountPercent, List<Offer> history, Map<Long, Integer> percentByOfferId) {
    if (discountPercent == null || offer.getCreatedAt() == null) {
      return null;
    }
    OffsetDateTime offerCreatedAt = offer.getCreatedAt();
    OffsetDateTime lastQualifyingCreatedAt = null;

    for (Offer candidate : history) {
      if (candidate.getId() != null && candidate.getId().equals(offer.getId())) {
        continue; // tiene que ser OTRA oferta.
      }
      if (!candidate.getCategory().equals(offer.getCategory())) {
        continue; // history trae varias categorías del batch a la vez.
      }
      OffsetDateTime candidateCreatedAt = candidate.getCreatedAt();
      if (candidateCreatedAt == null || !candidateCreatedAt.isBefore(offerCreatedAt)) {
        continue; // buscamos ANTES de esta oferta, nunca simultánea ni posterior.
      }
      Integer candidatePercent = percentByOfferId.get(candidate.getId());
      if (candidatePercent == null || candidatePercent < discountPercent) {
        continue;
      }
      if (lastQualifyingCreatedAt == null || candidateCreatedAt.isAfter(lastQualifyingCreatedAt)) {
        lastQualifyingCreatedAt = candidateCreatedAt;
      }
    }

    if (lastQualifyingCreatedAt == null) {
      return null; // sin historial previo — no se afirma nada.
    }
    long weeks = Duration.between(lastQualifyingCreatedAt, offerCreatedAt).toDays() / 7;
    if (weeks < FIRST_TIME_MIN_WEEKS) {
      return null; // hubo una hace menos de una semana.
    }
    return (int) Math.min(weeks, FIRST_TIME_MAX_WEEKS);
  }
}
