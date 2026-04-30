package com.fixy.backend.service;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.dto.ProviderCreateRequest;
import com.fixy.backend.dto.ProviderResponse;
import com.fixy.backend.dto.ProviderUpdateRequest;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.ProviderRepository;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

@Service
public class ProviderCatalogService {

  private final ProviderRepository providerRepository;

  public ProviderCatalogService(ProviderRepository providerRepository) {
    this.providerRepository = providerRepository;
  }

  public List<ProviderCatalogItem> list() {
    return providerRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toCatalogItem)
        .toList();
  }

  public List<ProviderResponse> listDetailed() {
    return providerRepository.findAllByOrderByCreatedAtDesc().stream()
        .map(this::toResponse)
        .toList();
  }

  public ProviderResponse get(Long id) {
    return toResponse(findProvider(id));
  }

  public ProviderResponse create(ProviderCreateRequest request) {
    Provider provider = new Provider();
    provider.setName(request.name().trim());
    provider.setPhone(request.phone().trim());
    provider.setWhatsappNumber(trimToNull(request.whatsappNumber()));
    provider.setSourceName(trimToNull(request.sourceName()));
    provider.setSourceType(defaultIfBlank(request.sourceType(), "manual"));
    provider.setPrimaryZone(trimToNull(request.primaryZone()));
    provider.setCoverageZones(normalizeCsv(request.coverageZones()));
    provider.setCity(trimToNull(request.city()));
    provider.setDepartment(trimToNull(request.department()));
    provider.setCategories(normalizeCsv(request.categories()));
    provider.setCategoryNotes(trimToNull(request.categoryNotes()));
    provider.setStatus(ProviderStatus.NEW);
    provider.setNotes(trimToNull(request.notes()));
    return toResponse(providerRepository.save(provider));
  }

  public ProviderResponse update(Long id, ProviderUpdateRequest request) {
    Provider provider = findProvider(id);

    if (request.name() != null) provider.setName(request.name().trim());
    if (request.phone() != null) provider.setPhone(request.phone().trim());
    if (request.whatsappNumber() != null) provider.setWhatsappNumber(trimToNull(request.whatsappNumber()));
    if (request.sourceName() != null) provider.setSourceName(trimToNull(request.sourceName()));
    if (request.sourceType() != null) provider.setSourceType(defaultIfBlank(request.sourceType(), "manual"));
    if (request.primaryZone() != null) provider.setPrimaryZone(trimToNull(request.primaryZone()));
    if (request.coverageZones() != null) provider.setCoverageZones(normalizeCsv(request.coverageZones()));
    if (request.city() != null) provider.setCity(trimToNull(request.city()));
    if (request.department() != null) provider.setDepartment(trimToNull(request.department()));
    if (request.categories() != null) provider.setCategories(normalizeCsv(request.categories()));
    if (request.categoryNotes() != null) provider.setCategoryNotes(trimToNull(request.categoryNotes()));
    if (request.status() != null) provider.setStatus(request.status());
    if (request.verificationStatus() != null) provider.setVerificationStatus(request.verificationStatus());
    if (request.ratingAverage() != null) provider.setRatingAverage(request.ratingAverage());
    if (request.ratingCount() != null) provider.setRatingCount(request.ratingCount());
    if (request.internalScore() != null) provider.setInternalScore(request.internalScore());
    if (request.riskFlags() != null) provider.setRiskFlags(normalizeCsv(request.riskFlags()));
    if (request.acceptedJobsCount() != null) provider.setAcceptedJobsCount(request.acceptedJobsCount());
    if (request.rejectedJobsCount() != null) provider.setRejectedJobsCount(request.rejectedJobsCount());
    if (request.completedJobsCount() != null) provider.setCompletedJobsCount(request.completedJobsCount());
    if (request.notes() != null) provider.setNotes(trimToNull(request.notes()));

    return toResponse(providerRepository.save(provider));
  }

  public List<ProviderCatalogItem> findMatches(String category, String location) {
    String normalizedCategory = normalize(category);
    String normalizedLocation = normalize(location);

    return providerRepository.findAll().stream()
        .filter(provider -> provider.getStatus() != ProviderStatus.BLOCKED)
        .filter(provider -> provider.getStatus() != ProviderStatus.INACTIVE)
        .filter(provider -> matchesCategory(provider, normalizedCategory))
        .filter(provider -> matchesLocation(provider, normalizedLocation))
        .map(provider -> toCatalogItem(provider, normalizedCategory))
        .toList();
  }

  private boolean matchesCategory(Provider provider, String category) {
    if (category.isBlank()) {
      return true;
    }
    return splitCsv(provider.getCategories()).stream()
        .map(this::normalize)
        .anyMatch(value -> value.equals(category));
  }

  private boolean matchesLocation(Provider provider, String location) {
    if (location.isBlank()) {
      return true;
    }

    if (normalize(provider.getPrimaryZone()).equals(location)) {
      return true;
    }

    if (splitCsv(provider.getCoverageZones()).stream().map(this::normalize).anyMatch(value -> value.equals(location))) {
      return true;
    }

    return normalize(provider.getCity()).equals(location);
  }

  private Provider findProvider(Long id) {
    return providerRepository.findById(id)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "provider not found"));
  }

  private ProviderCatalogItem toCatalogItem(Provider provider) {
    return toCatalogItem(provider, "");
  }

  private ProviderCatalogItem toCatalogItem(Provider provider, String matchedCategory) {
    String primaryCategory = splitCsv(provider.getCategories()).stream().findFirst().orElse("");
    String displayCategory = splitCsv(provider.getCategories()).stream()
        .filter(category -> normalize(category).equals(matchedCategory))
        .findFirst()
        .orElse(primaryCategory);
    String zone = firstNonBlank(provider.getPrimaryZone(), provider.getCity());
    return new ProviderCatalogItem(
        provider.getId(),
        provider.getName(),
        displayCategory,
        zone,
        firstNonBlank(provider.getWhatsappNumber(), provider.getPhone()),
        provider.getStatus().name(),
        provider.getSourceType()
    );
  }

  private ProviderResponse toResponse(Provider provider) {
    return new ProviderResponse(
        provider.getId(),
        provider.getName(),
        provider.getPhone(),
        provider.getWhatsappNumber(),
        provider.getSourceName(),
        provider.getSourceType(),
        provider.getPrimaryZone(),
        splitCsv(provider.getCoverageZones()),
        provider.getCity(),
        provider.getDepartment(),
        splitCsv(provider.getCategories()),
        provider.getCategoryNotes(),
        provider.getStatus(),
        provider.getVerificationStatus(),
        provider.getRatingAverage(),
        provider.getRatingCount(),
        provider.getInternalScore(),
        splitCsv(provider.getRiskFlags()),
        provider.getLastContactedAt(),
        provider.getLastRespondedAt(),
        provider.getAcceptedJobsCount(),
        provider.getRejectedJobsCount(),
        provider.getCompletedJobsCount(),
        provider.getNotes(),
        provider.getCreatedAt(),
        provider.getUpdatedAt()
    );
  }

  private String normalize(String value) {
    return value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
  }

  private List<String> splitCsv(String raw) {
    if (raw == null || raw.isBlank()) {
      return List.of();
    }
    return Arrays.stream(raw.split(","))
        .map(String::trim)
        .filter(value -> !value.isBlank())
        .toList();
  }

  private String normalizeCsv(String raw) {
    return String.join(", ", splitCsv(raw));
  }

  private String trimToNull(String value) {
    if (value == null) {
      return null;
    }
    String trimmed = value.trim();
    return trimmed.isBlank() ? null : trimmed;
  }

  private String defaultIfBlank(String value, String fallback) {
    String trimmed = trimToNull(value);
    return trimmed == null ? fallback : trimmed;
  }

  private String firstNonBlank(String first, String second) {
    return Objects.requireNonNullElseGet(trimToNull(first), () -> Objects.requireNonNullElse(trimToNull(second), ""));
  }
}
