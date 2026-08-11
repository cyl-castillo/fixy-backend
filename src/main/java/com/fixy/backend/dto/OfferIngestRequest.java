package com.fixy.backend.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record OfferIngestRequest(
    @NotEmpty(message = "offers must not be empty") @Valid List<OfferIngestItem> offers
) {
}
