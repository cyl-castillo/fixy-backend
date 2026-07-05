const statuses = [
  "NEW",
  "IN_REVIEW",
  "PROVIDER_CONTACTED",
  "ASSIGNED",
  "IN_PROGRESS",
  "COMPLETED",
  "CANCELLED",
];

const boardStatuses = ["NEW", "IN_REVIEW", "ASSIGNED", "COMPLETED"];

const leadsContainer = document.getElementById("leadsContainer");
const boardContainer = document.getElementById("boardContainer");
const summaryText = document.getElementById("summaryText");
const statsGrid = document.getElementById("statsGrid");
const feedback = document.getElementById("feedback");
const createLeadForm = document.getElementById("createLeadForm");
const createProviderForm = document.getElementById("createProviderForm");
const providersContainer = document.getElementById("providersContainer");
const providerSummaryText = document.getElementById("providerSummaryText");
const statusFilter = document.getElementById("statusFilter");
const urgencyFilter = document.getElementById("urgencyFilter");
const assignmentFilter = document.getElementById("assignmentFilter");
const providerFilter = document.getElementById("providerFilter");
const searchInput = document.getElementById("searchInput");
const refreshButton = document.getElementById("refreshButton");
const clearFiltersButton = document.getElementById("clearFiltersButton");

let allLeads = [];
let providerCatalog = [];

function showFeedback(message, isError = false) {
  feedback.hidden = false;
  feedback.textContent = message;
  feedback.classList.toggle("error", isError);
}

function clearFeedback() {
  feedback.hidden = true;
  feedback.textContent = "";
  feedback.classList.remove("error");
}

async function request(path, options = {}) {
  const response = await fetch(path, {
    headers: { "Content-Type": "application/json", ...(options.headers || {}) },
    ...options,
  });

  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `HTTP ${response.status}`);
  }

  return response.json();
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#39;");
}

function getKnownProviders(leads) {
  const fromCatalog = providerCatalog.map((provider) => provider.name);
  const fromLeads = leads.map((lead) => (lead.assignedProvider || "").trim()).filter(Boolean);
  return [...new Set([...fromCatalog, ...fromLeads])].sort();
}

function normalize(value) {
  return String(value || "").trim().toLowerCase();
}

function providerCategories(provider) {
  return Array.isArray(provider.categories)
    ? provider.categories
    : provider.category ? [provider.category] : [];
}

function providerZones(provider) {
  const zones = [];
  if (provider.primaryZone) zones.push(provider.primaryZone);
  if (Array.isArray(provider.coverageZones)) zones.push(...provider.coverageZones);
  if (!zones.length && provider.zone) zones.push(provider.zone);
  if (!zones.length && provider.city) zones.push(provider.city);
  return zones;
}

function findSuggestedProviders(lead) {
  const category = normalize(lead.detectedCategory);
  const location = normalize(lead.location);

  const exact = providerCatalog.filter((provider) =>
    providerCategories(provider).some((item) => normalize(item) === category)
    && providerZones(provider).some((item) => normalize(item) === location)
  );
  const categoryOnly = providerCatalog.filter((provider) =>
    providerCategories(provider).some((item) => normalize(item) === category)
    && !exact.some((item) => item.name === provider.name)
  );
  const zoneOnly = providerCatalog.filter((provider) =>
    providerZones(provider).some((item) => normalize(item) === location)
      && !providerCategories(provider).some((item) => normalize(item) === category)
      && !exact.some((item) => item.name === provider.name)
      && !categoryOnly.some((item) => item.name === provider.name)
  );

  return [...exact, ...categoryOnly, ...zoneOnly].slice(0, 3);
}

function fillProviderFilter(leads) {
  const current = providerFilter.value;
  const providers = getKnownProviders(leads);
  providerFilter.innerHTML = '<option value="">Todos</option>' + providers
    .map((provider) => `<option value="${escapeHtml(provider)}">${escapeHtml(provider)}</option>`)
    .join("");

  if (providers.includes(current)) {
    providerFilter.value = current;
  }
}

function matchesSearch(lead, term) {
  if (!term) return true;
  const haystack = [
    lead.name,
    lead.phone,
    lead.problem,
    lead.location,
    lead.detectedCategory,
    lead.assignedProvider,
    lead.status,
    lead.notes,
  ].join(" ").toLowerCase();

  return haystack.includes(term.toLowerCase());
}

function getFilteredLeads() {
  const selectedStatus = statusFilter.value;
  const selectedUrgency = urgencyFilter.value;
  const selectedAssignment = assignmentFilter.value;
  const selectedProvider = providerFilter.value;
  const searchTerm = searchInput.value.trim();

  return allLeads.filter((lead) => {
    const statusMatch = !selectedStatus || lead.status === selectedStatus;
    const urgencyMatch = !selectedUrgency || lead.urgency === selectedUrgency;
    const assignmentMatch = !selectedAssignment
      || (selectedAssignment === "assigned" && !!(lead.assignedProvider || "").trim())
      || (selectedAssignment === "unassigned" && !(lead.assignedProvider || "").trim());
    const providerMatch = !selectedProvider || (lead.assignedProvider || "") === selectedProvider;

    return statusMatch && urgencyMatch && assignmentMatch && providerMatch && matchesSearch(lead, searchTerm);
  });
}

function renderStats(leads) {
  const urgent = leads.filter((lead) => lead.urgency === "alta").length;
  const unassigned = leads.filter((lead) => !lead.assignedProvider).length;
  const active = leads.filter((lead) => !["COMPLETED", "CANCELLED"].includes(lead.status)).length;

  statsGrid.innerHTML = `
    <article class="stat-card"><small>Total visibles</small><strong>${leads.length}</strong></article>
    <article class="stat-card"><small>Urgentes</small><strong>${urgent}</strong></article>
    <article class="stat-card"><small>Sin asignar</small><strong>${unassigned}</strong></article>
    <article class="stat-card"><small>Activos</small><strong>${active}</strong></article>
  `;
}

function renderBoard(leads) {
  boardContainer.innerHTML = boardStatuses.map((status) => {
    const items = leads.filter((lead) => lead.status === status);
    const cards = items.length
      ? items.map((lead) => `
          <article class="board-card">
            <strong>#${lead.id} · ${escapeHtml(lead.name || "Sin nombre")}</strong>
            <p>${escapeHtml(lead.problem)}</p>
            <small>${escapeHtml(lead.location || "sin ubicación")} · ${escapeHtml(lead.detectedCategory || "sin categoría")}</small>
          </article>
        `).join("")
      : '<p class="empty">Sin leads</p>';

    return `
      <section class="board-column">
        <h3>${status}</h3>
        <small>${items.length} lead(s)</small>
        <div class="board-list">${cards}</div>
      </section>
    `;
  }).join("");
}

function quickActionsHtml(lead, suggestions) {
  const firstSuggested = suggestions[0]?.name || "";
  return `
    <div class="quick-actions">
      <button class="btn btn-secondary quick-action" type="button" data-action="review">Tomar caso</button>
      <button class="btn btn-secondary quick-action" type="button" data-action="suggested" ${firstSuggested ? "" : "disabled"}>Asignar sugerido</button>
      <button class="btn btn-secondary quick-action" type="button" data-action="progress">En progreso</button>
      <button class="btn btn-secondary quick-action" type="button" data-action="complete">Completar</button>
      <button class="btn btn-secondary quick-action danger" type="button" data-action="cancel">Cancelar</button>
    </div>
  `;
}

function discoveredProviderFormHtml(lead) {
  return `
    <details class="lead-notes">
      <summary><strong>Registrar proveedor descubierto</strong></summary>
      <form class="discovered-provider-form" data-lead-id="${lead.id}">
        <label>
          Nombre
          <input name="name" type="text" placeholder="Proveedor encontrado" required />
        </label>
        <label>
          Teléfono
          <input name="phone" type="text" placeholder="099123456" required />
        </label>
        <label>
          WhatsApp
          <input name="whatsappNumber" type="text" placeholder="099123456" />
        </label>
        <label>
          Categorías
          <input name="categories" type="text" value="${escapeHtml(lead.detectedCategory || "")}" placeholder="plomeria" />
        </label>
        <label>
          Zona principal
          <input name="primaryZone" type="text" value="${escapeHtml(lead.location || "")}" placeholder="Solymar" />
        </label>
        <label>
          Cobertura
          <input name="coverageZones" type="text" placeholder="Solymar, Lagomar, El Pinar" />
        </label>
        <label>
          Fuente
          <input name="sourceName" type="text" placeholder="Google Maps, Facebook, directorio, etc." />
        </label>
        <label>
          Notas
          <textarea name="notes" rows="2" placeholder="Cómo apareció, disponibilidad, contexto, etc."></textarea>
        </label>
        <label>
          <input name="assignToLead" type="checkbox" checked />
          Asignar directamente a este lead
        </label>
        <button class="btn btn-primary" type="submit">Guardar proveedor descubierto</button>
      </form>
    </details>
  `;
}

function renderLeadCard(lead) {
  const statusOptions = statuses
    .map((status) => `<option value="${status}" ${lead.status === status ? "selected" : ""}>${status}</option>`)
    .join("");
  // El select envía el id del proveedor para que el backend resuelva el
  // nombre canónico. Para legacy (lead asignado a un nombre que ya no está
  // en el catálogo), agregamos una opción extra "mantener" con prefix "name:".
  const catalogOptions = providerCatalog
    .map((provider) => {
      const matched = lead.assignedProviderId
        ? provider.id === lead.assignedProviderId
        : !lead.assignedProviderId && lead.assignedProvider === provider.name;
      return `<option value="id:${provider.id}" ${matched ? "selected" : ""}>${escapeHtml(provider.name)}</option>`;
    })
    .join("");
  const legacyName = (lead.assignedProvider || "").trim();
  const legacyOption = legacyName && !providerCatalog.some((p) => p.name === legacyName)
    ? `<option value="name:${escapeHtml(legacyName)}" selected>(legacy) ${escapeHtml(legacyName)}</option>`
    : "";
  const providerOptions = legacyOption + catalogOptions;
  const suggestions = findSuggestedProviders(lead);
  const suggestionHtml = suggestions.length
    ? `<div class="lead-suggestions"><strong>Sugeridos:</strong> ${suggestions.map((provider) => `<button class="suggestion-chip" type="button" data-provider-id="${provider.id}" data-provider="${escapeHtml(provider.name)}">${escapeHtml(provider.name)} · ${escapeHtml(provider.primaryZone || provider.zone || provider.city || "sin zona")}</button>`).join("")}</div>`
    : '<div class="lead-suggestions"><strong>Sugeridos:</strong> <span class="empty-inline">Sin match claro</span></div>';
  const urgencyClass = lead.urgency === "alta" ? "urgent" : "";

  return `
    <article class="lead-card" data-id="${lead.id}" data-suggested="${escapeHtml(suggestions[0]?.name || "")}" data-suggested-id="${suggestions[0]?.id ?? ""}">
      <div class="lead-top">
        <div><h3>#${lead.id} · ${escapeHtml(lead.name || "Sin nombre")}</h3></div>
        <div class="badges">
          <span class="badge">${escapeHtml(lead.detectedCategory || "sin categoría")}</span>
          <span class="badge ${urgencyClass}">${escapeHtml(lead.urgency || "sin urgencia")}</span>
          <span class="badge status">${escapeHtml(lead.status)}</span>
          ${lead.disputed ? '<span class="badge disputed">EN DISPUTA</span>' : ""}
        </div>
      </div>
      <div class="lead-meta">
        <span>📞 ${escapeHtml(lead.phone || "sin teléfono")}</span>
        <span>📍 ${escapeHtml(lead.location || "sin ubicación")}</span>
        <span>👷 ${escapeHtml(lead.assignedProvider || "sin asignar")}</span>
      </div>
      <p class="lead-problem">${escapeHtml(lead.problem)}</p>
      ${suggestionHtml}
      ${quickActionsHtml(lead, suggestions)}
      <div class="lead-notes">
        <label>
          Notas
          <textarea class="notes-input" rows="3" placeholder="Agregá contexto, seguimiento o acuerdos">${escapeHtml(lead.notes || "")}</textarea>
        </label>
      </div>
      ${discoveredProviderFormHtml(lead)}
      <div class="lead-history">
        <label>
          Historial
          <pre>${escapeHtml(lead.history || "Sin movimientos todavía")}</pre>
        </label>
      </div>
      <div class="lead-actions">
        <small>Actualizado: ${escapeHtml(new Date(lead.updatedAt).toLocaleString())}</small>
        <div class="controls">
          <select class="status-select">${statusOptions}</select>
          <select class="provider-select"><option value="">Sin asignar</option>${providerOptions}</select>
          <button class="btn btn-primary save-button" type="button">Guardar</button>
        </div>
      </div>
    </article>
  `;
}

function renderProviderCard(provider) {
  const categories = providerCategories(provider).join(", ") || "sin categoría";
  const zones = providerZones(provider).join(", ") || "sin zona";
  return `
    <article class="lead-card" data-provider-id="${provider.id}">
      <div class="lead-top">
        <div><h3>${escapeHtml(provider.name)}</h3></div>
        <div class="badges">
          <span class="badge">${escapeHtml(categories)}</span>
          <span class="badge status">${escapeHtml(provider.status || "NEW")}</span>
        </div>
      </div>
      <div class="lead-meta">
        <span>📞 ${escapeHtml(provider.whatsappNumber || provider.phone || "sin teléfono")}</span>
        <span>📍 ${escapeHtml(zones)}</span>
        <span>🧭 ${escapeHtml(provider.sourceType || "manual")}</span>
      </div>
      <p class="lead-problem">${escapeHtml(provider.notes || "Proveedor cargado en base")}</p>
      <div class="provider-actions">
        <button type="button" class="btn btn-secondary magic-link-button">Generar link de acceso</button>
        <p class="magic-link-output" hidden></p>
      </div>
    </article>
  `;
}

async function generateProviderMagicLink(button) {
  const card = button.closest('article[data-provider-id]');
  if (!card) return;
  const providerId = card.dataset.providerId;
  const output = card.querySelector('.magic-link-output');
  button.disabled = true;
  button.textContent = 'Generando…';
  try {
    const data = await request(`/api/providers/${providerId}/access-token`, { method: 'POST' });
    output.hidden = false;
    output.innerHTML = `
      <a href="${escapeHtml(data.url)}" target="_blank" rel="noopener">${escapeHtml(data.url)}</a>
      <button type="button" class="btn btn-secondary copy-link-button" data-link="${escapeHtml(data.url)}">Copiar</button>
    `;
  } catch (err) {
    output.hidden = false;
    output.textContent = 'No pudimos generar el link. Probá de nuevo.';
  } finally {
    button.disabled = false;
    button.textContent = 'Regenerar link';
  }
}

async function copyMagicLink(button) {
  const url = button.dataset.link;
  if (!url) return;
  try {
    await navigator.clipboard.writeText(url);
    const original = button.textContent;
    button.textContent = '¡Copiado!';
    setTimeout(() => { button.textContent = original; }, 1500);
  } catch {
    // fallback noop
  }
}

async function saveLead(card, overrides = {}) {
  const id = card.dataset.id;
  const status = overrides.status ?? card.querySelector('.status-select').value;
  const notes = overrides.notes ?? card.querySelector('.notes-input').value;

  const payload = { status, notes };

  if (overrides.assignedProviderId != null) {
    payload.assignedProviderId = overrides.assignedProviderId;
  } else if (overrides.assignedProvider != null) {
    payload.assignedProvider = overrides.assignedProvider;
  } else {
    const rawValue = card.querySelector('.provider-select').value;
    if (rawValue.startsWith('id:')) {
      payload.assignedProviderId = Number(rawValue.slice(3));
    } else if (rawValue.startsWith('name:')) {
      payload.assignedProvider = rawValue.slice(5);
    } else {
      // "Sin asignar" → desasigna por nombre (compat con backend actual).
      payload.assignedProvider = '';
    }
  }

  await request(`/api/leads/${id}`, {
    method: 'PATCH',
    body: JSON.stringify(payload),
  });
}

async function submitDiscoveredProvider(form) {
  const leadId = form.dataset.leadId;
  const formData = new FormData(form);
  const payload = {
    name: formData.get('name')?.toString().trim() || '',
    phone: formData.get('phone')?.toString().trim() || '',
    whatsappNumber: formData.get('whatsappNumber')?.toString().trim() || null,
    sourceName: formData.get('sourceName')?.toString().trim() || null,
    primaryZone: formData.get('primaryZone')?.toString().trim() || null,
    coverageZones: formData.get('coverageZones')?.toString().trim() || null,
    categories: formData.get('categories')?.toString().trim() || null,
    notes: formData.get('notes')?.toString().trim() || null,
    assignToLead: formData.get('assignToLead') === 'on',
  };

  return request(`/api/leads/${leadId}/discovered-provider`, {
    method: 'POST',
    body: JSON.stringify(payload),
  });
}

function bindSaveButtons() {
  document.querySelectorAll('.save-button').forEach((button) => {
    button.addEventListener('click', async () => {
      const card = button.closest('.lead-card');
      try {
        await saveLead(card);
        showFeedback(`Lead #${card.dataset.id} actualizado.`);
        await loadLeads();
      } catch {
        showFeedback(`No pude actualizar el lead #${card.dataset.id}.`, true);
      }
    });
  });

  document.querySelectorAll('.suggestion-chip').forEach((button) => {
    button.addEventListener('click', () => {
      const card = button.closest('.lead-card');
      const select = card.querySelector('.provider-select');
      const providerId = button.dataset.providerId;
      if (providerId) {
        select.value = `id:${providerId}`;
      } else {
        select.value = `name:${button.dataset.provider}`;
      }
    });
  });

  document.querySelectorAll('.quick-action').forEach((button) => {
    button.addEventListener('click', async () => {
      const card = button.closest('.lead-card');
      const action = button.dataset.action;
      const suggested = card.dataset.suggested || '';
      const suggestedId = card.dataset.suggestedId ? Number(card.dataset.suggestedId) : null;
      const currentNotes = card.querySelector('.notes-input').value;
      const quickNotes = {
        review: currentNotes,
        suggested: currentNotes,
        progress: currentNotes,
        complete: currentNotes,
        cancel: currentNotes,
      };

      const suggestedConfig = suggestedId
        ? { status: 'ASSIGNED', assignedProviderId: suggestedId, notes: quickNotes.suggested }
        : { status: 'ASSIGNED', assignedProvider: suggested, notes: quickNotes.suggested };

      const config = {
        review: { status: 'IN_REVIEW', notes: quickNotes.review },
        suggested: suggestedConfig,
        progress: { status: 'IN_PROGRESS', notes: quickNotes.progress },
        complete: { status: 'COMPLETED', notes: quickNotes.complete },
        cancel: { status: 'CANCELLED', notes: quickNotes.cancel },
      }[action];

      try {
        await saveLead(card, config);
        showFeedback(`Acción rápida aplicada en lead #${card.dataset.id}.`);
        await loadLeads();
      } catch {
        showFeedback(`No pude aplicar la acción rápida al lead #${card.dataset.id}.`, true);
      }
    });
  });

  document.querySelectorAll('.discovered-provider-form').forEach((form) => {
    form.addEventListener('submit', async (event) => {
      event.preventDefault();
      try {
        const result = await submitDiscoveredProvider(form);
        showFeedback(result.message || `Proveedor descubierto registrado para lead #${form.dataset.leadId}.`);
        await loadLeads();
      } catch {
        showFeedback(`No pude registrar proveedor descubierto para lead #${form.dataset.leadId}.`, true);
      }
    });
  });
}

function render() {
  const leads = getFilteredLeads();
  summaryText.textContent = `${leads.length} lead(s) visibles`;
  providerSummaryText.textContent = `${providerCatalog.length} proveedor(es) en base`;
  renderStats(leads);
  renderBoard(leads);

  if (!leads.length) {
    leadsContainer.innerHTML = '<p class="empty">No hay leads para este filtro.</p>';
  } else {
    leadsContainer.innerHTML = leads.map(renderLeadCard).join('');
    bindSaveButtons();
  }

  providersContainer.innerHTML = providerCatalog.length
    ? providerCatalog.map(renderProviderCard).join('')
    : '<p class="empty">Todavía no hay proveedores cargados.</p>';
  bindProviderActions();
}

// Event delegation para botones del catálogo de proveedores. Esto cubre
// también botones añadidos dinámicamente (ej. "Copiar" que aparece tras
// generar el link).
let providerActionsBound = false;
function bindProviderActions() {
  if (providerActionsBound) return;
  providerActionsBound = true;
  providersContainer.addEventListener('click', (event) => {
    const target = event.target;
    if (!(target instanceof HTMLElement)) return;
    if (target.matches('.magic-link-button')) {
      generateProviderMagicLink(target);
    } else if (target.matches('.copy-link-button')) {
      copyMagicLink(target);
    }
  });
}

function formatPercentage(value) {
  return `${Number(value ?? 0).toFixed(1)}%`;
}

function formatDurationSeconds(seconds) {
  if (seconds == null) return "Sin datos";
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return `${minutes} min`;
  const hours = (minutes / 60).toFixed(1);
  return `${hours} h`;
}

function renderOpsMetrics(metrics) {
  const opsMetricsBody = document.getElementById("opsMetricsBody");
  const opsMetricsNote = document.getElementById("opsMetricsNote");
  if (!opsMetricsBody) return;

  opsMetricsBody.innerHTML = `
    <tr>
      <td><strong>${metrics.totalLeadsCreated}</strong></td>
      <td><strong>${formatPercentage(metrics.fillRatePercentage)}</strong></td>
      <td><strong>${formatDurationSeconds(metrics.medianTimeToFirstResponseSeconds)}</strong></td>
      <td><strong>${formatPercentage(metrics.repeatRateAutodeclaredPercentage)}</strong></td>
    </tr>
  `;

  if (opsMetricsNote) {
    opsMetricsNote.textContent =
      `Mediana calculada sobre ${metrics.leadsConsideredForResponseTime} lead(s) con respuesta registrada. `
      + `Repeat rate autodeclarado sobre ${metrics.distinctClientsWithCompleted} cliente(s) con al menos un servicio completado `
      + `(COMPLETED es autodeclaración del proveedor, sin confirmación del cliente todavía).`;
  }
}

async function loadOpsMetrics() {
  const opsMetricsBody = document.getElementById("opsMetricsBody");
  try {
    const metrics = await request('/api/ops/metrics/daily');
    renderOpsMetrics(metrics);
  } catch {
    if (opsMetricsBody) {
      opsMetricsBody.innerHTML = '<tr><td colspan="4">No pude cargar las métricas.</td></tr>';
    }
  }
}

function formatMoney(amount, currency) {
  if (amount == null) return "—";
  return `${escapeHtml(currency || "UYU")} ${Number(amount).toFixed(2)}`;
}

function formatDateTime(value) {
  if (!value) return "—";
  return new Date(value).toLocaleString();
}

function renderOpsPayments(payments) {
  const opsPaymentsBody = document.getElementById("opsPaymentsBody");
  if (!opsPaymentsBody) return;

  if (!payments.length) {
    opsPaymentsBody.innerHTML = '<tr><td colspan="8">No hay comisiones para este filtro.</td></tr>';
    return;
  }

  opsPaymentsBody.innerHTML = payments.map((payment) => `
    <tr>
      <td>#${payment.leadId}</td>
      <td>#${payment.providerId}</td>
      <td>${formatMoney(payment.amountCharged, payment.currency)}</td>
      <td>${formatMoney(payment.commissionAmount, payment.currency)}</td>
      <td><span class="badge status">${escapeHtml(payment.commissionStatus)}</span></td>
      <td>${payment.mpPaymentLink
        ? `<a href="${escapeHtml(payment.mpPaymentLink)}" target="_blank" rel="noopener">Link</a>`
        : "—"}</td>
      <td>${formatDateTime(payment.createdAt)}</td>
      <td>${formatDateTime(payment.paidAt)}</td>
    </tr>
  `).join("");
}

async function loadOpsPayments() {
  const opsPaymentsBody = document.getElementById("opsPaymentsBody");
  const statusFilterEl = document.getElementById("paymentsStatusFilter");
  try {
    const status = statusFilterEl?.value || "";
    const query = status ? `?status=${encodeURIComponent(status)}` : "";
    const payments = await request(`/api/ops/payments${query}`);
    renderOpsPayments(payments);
  } catch {
    if (opsPaymentsBody) {
      opsPaymentsBody.innerHTML = '<tr><td colspan="8">No pude cargar las comisiones.</td></tr>';
    }
  }
}

document.getElementById("paymentsStatusFilter")?.addEventListener("change", loadOpsPayments);

async function loadLeads() {
  clearFeedback();
  leadsContainer.innerHTML = '<p class="empty">Cargando…</p>';
  const [providers, leads] = await Promise.all([
    request('/api/providers'),
    request('/api/leads'),
  ]);
  providerCatalog = providers;
  allLeads = leads;
  fillProviderFilter(allLeads);
  render();
  loadOpsMetrics();
  loadOpsPayments();
}

createLeadForm.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFeedback();
  const formData = new FormData(createLeadForm);
  const payload = Object.fromEntries(formData.entries());

  try {
    const lead = await request('/api/leads', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    createLeadForm.reset();
    createLeadForm.channel.value = 'whatsapp';
    showFeedback(`Lead #${lead.id} creado correctamente.`);
    await loadLeads();
  } catch {
    showFeedback('No pude crear el lead. Revisá el problema y probá de nuevo.', true);
  }
});

createProviderForm?.addEventListener('submit', async (event) => {
  event.preventDefault();
  clearFeedback();
  const formData = new FormData(createProviderForm);
  const payload = Object.fromEntries(formData.entries());

  try {
    const provider = await request('/api/providers', {
      method: 'POST',
      body: JSON.stringify(payload),
    });
    createProviderForm.reset();
    showFeedback(`Proveedor ${provider.name} creado correctamente.`);
    await loadLeads();
  } catch {
    showFeedback('No pude crear el proveedor. Revisá los datos y probá de nuevo.', true);
  }
});

function clearFilters() {
  statusFilter.value = '';
  urgencyFilter.value = '';
  assignmentFilter.value = '';
  providerFilter.value = '';
  searchInput.value = '';
  render();
}

statusFilter.addEventListener('change', render);
urgencyFilter.addEventListener('change', render);
assignmentFilter.addEventListener('change', render);
providerFilter.addEventListener('change', render);
searchInput.addEventListener('input', render);
refreshButton.addEventListener('click', loadLeads);
clearFiltersButton.addEventListener('click', clearFilters);

document.addEventListener('DOMContentLoaded', () => {
  createLeadForm.channel.value = 'whatsapp';
  loadLeads().catch(() => {
    showFeedback('No pude cargar los leads iniciales.', true);
  });
});
