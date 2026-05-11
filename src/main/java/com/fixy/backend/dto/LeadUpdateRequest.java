package com.fixy.backend.dto;

import com.fixy.backend.model.LeadStatus;

/**
 * Update interno del lead. Si viene {@code assignedProviderId}, el server
 * busca el provider, setea el id en el lead y sobreescribe
 * {@code assignedProvider} con el nombre del provider para compatibilidad.
 */
public record LeadUpdateRequest(
    LeadStatus status,
    String assignedProvider,
    Long assignedProviderId,
    String notes
) {
}
