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

function findSuggestedProviders(lead) {
  const category = (lead.detectedCategory || "").toLowerCase();
  const location = (lead.location || "").toLowerCase();

  const exact = providerCatalog.filter((provider) =>
    provider.category.toLowerCase() === category && provider.zone.toLowerCase() === location
  );
  const categoryOnly = providerCatalog.filter((provider) =>
    provider.category.toLowerCase() === category && !exact.some((item) => item.name === provider.name)
  );
  const zoneOnly = providerCatalog.filter((provider) =>
    provider.zone.toLowerCase() === location
      && provider.category.toLowerCase() !== category
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

function renderLeadCard(lead) {
  const statusOptions = statuses
    .map((status) => `<option value="${status}" ${lead.status === status ? "selected" : ""}>${status}</option>`)
    .join("");
  const providerOptions = getKnownProviders(allLeads)
    .map((provider) => `<option value="${escapeHtml(provider)}" ${lead.assignedProvider === provider ? "selected" : ""}>${escapeHtml(provider)}</option>`)
    .join("");
  const suggestions = findSuggestedProviders(lead);
  const suggestionHtml = suggestions.length
    ? `<div class="lead-suggestions"><strong>Sugeridos:</strong> ${suggestions.map((provider) => `<button class="suggestion-chip" type="button" data-provider="${escapeHtml(provider.name)}">${escapeHtml(provider.name)} · ${escapeHtml(provider.zone)}</button>`).join("")}</div>`
    : '<div class="lead-suggestions"><strong>Sugeridos:</strong> <span class="empty-inline">Sin match claro</span></div>';
  const urgencyClass = lead.urgency === "alta" ? "urgent" : "";

  return `
    <article class="lead-card" data-id="${lead.id}" data-suggested="${escapeHtml(suggestions[0]?.name || "")}">
      <div class="lead-top">
        <div><h3>#${lead.id} · ${escapeHtml(lead.name || "Sin nombre")}</h3></div>
        <div class="badges">
          <span class="badge">${escapeHtml(lead.detectedCategory || "sin categoría")}</span>
          <span class="badge ${urgencyClass}">${escapeHtml(lead.urgency || "sin urgencia")}</span>
          <span class="badge status">${escapeHtml(lead.status)}</span>
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

async function saveLead(card, overrides = {}) {
  const id = card.dataset.id;
  const status = overrides.status ?? card.querySelector('.status-select').value;
  const assignedProvider = overrides.assignedProvider ?? card.querySelector('.provider-select').value;
  const notes = overrides.notes ?? card.querySelector('.notes-input').value;

  await request(`/api/leads/${id}`, {
    method: 'PATCH',
    body: JSON.stringify({ status, assignedProvider, notes }),
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
      card.querySelector('.provider-select').value = button.dataset.provider;
    });
  });

  document.querySelectorAll('.quick-action').forEach((button) => {
    button.addEventListener('click', async () => {
      const card = button.closest('.lead-card');
      const action = button.dataset.action;
      const suggested = card.dataset.suggested || '';
      const currentNotes = card.querySelector('.notes-input').value;
      const quickNotes = {
        review: currentNotes,
        suggested: currentNotes,
        progress: currentNotes,
        complete: currentNotes,
        cancel: currentNotes,
      };

      const config = {
        review: { status: 'IN_REVIEW', notes: quickNotes.review },
        suggested: { status: 'ASSIGNED', assignedProvider: suggested, notes: quickNotes.suggested },
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
}

function render() {
  const leads = getFilteredLeads();
  summaryText.textContent = `${leads.length} lead(s) visibles`;
  renderStats(leads);
  renderBoard(leads);

  if (!leads.length) {
    leadsContainer.innerHTML = '<p class="empty">No hay leads para este filtro.</p>';
    return;
  }

  leadsContainer.innerHTML = leads.map(renderLeadCard).join('');
  bindSaveButtons();
}

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
