const API_BASE_URL = "";

const state = {
  leadId: null,
  lead: null,
  stage: "idle",
  transcript: [],
  mode: "client",
};

function setCurrentYear() {
  const yearElement = document.getElementById("year");
  if (yearElement) {
    yearElement.textContent = new Date().getFullYear();
  }
}

function showLeadFormFeedback(message, isError = false) {
  const feedback = document.getElementById("lead-form-feedback");
  if (!feedback) return;
  feedback.hidden = false;
  feedback.textContent = message;
  feedback.classList.toggle("error", isError);
}

function pushTranscript(role, text) {
  if (!text) return;
  state.transcript.push({ role, text });
  renderTranscript();
}

function renderTranscript() {
  const transcript = document.getElementById("chat-transcript");
  if (!transcript) return;

  transcript.innerHTML = "";
  state.transcript.forEach((entry) => {
    const item = document.createElement("article");
    item.className = `chat-bubble ${entry.role === "user" ? "chat-bubble-user" : "chat-bubble-agent"}`;

    const speaker = document.createElement("p");
    speaker.className = "chat-speaker";
    speaker.textContent = entry.role === "user" ? "Tú" : "Fixy";

    const message = document.createElement("p");
    message.textContent = entry.text;

    item.append(speaker, message);
    transcript.appendChild(item);
  });

  transcript.scrollTop = transcript.scrollHeight;
}

function humanizeNextAction(action) {
  switch (action) {
    case "ask_location":
      return "Decirme tu zona";
    case "ask_service_category":
      return "Confirmar el tipo de servicio";
    case "generate_matches":
      return "Ver mis mejores opciones";
    case "present_matches":
      return "Elegir entre las opciones";
    case "ampliar_busqueda_o_handoff":
      return "Ampliar la búsqueda";
    default:
      return "Seguir con el caso";
  }
}

function formatField(field) {
  const labels = {
    zona: "tu zona",
    categoria: "el tipo de servicio",
    "foto del problema": "si tienes una foto",
    "direccion exacta": "la dirección exacta",
  };
  return labels[field] || field;
}

function getProgressValue(lead) {
  if (!lead) return 0;
  if (lead.readyForMatching) return 80;
  if ((lead.blockingFields || []).length === 0) return 60;
  return 35;
}

function getScenarioReplies(lead) {
  const category = (lead.detectedCategory || "").toLowerCase();

  if (category === "electricidad") {
    return ["Sin luz total", "Salta la llave", "Solo una parte", "Huele a quemado"];
  }

  if (category === "plomeria") {
    return ["Pierde agua", "Se tapó", "No sale agua", "Es urgente"];
  }

  if (category === "cerrajeria") {
    return ["No puedo entrar", "Llave rota", "Puerta trabada", "Es urgente"];
  }

  return ["Es urgente", "Tengo foto", "Es en apartamento", "Quiero resolverlo hoy"];
}

function getQuickReplies(lead) {
  const quickReplies = [];
  if ((lead.blockingFields || []).includes("zona")) {
    ["Pocitos", "Cordón", "Centro", "Malvín"].forEach((zone) => {
      quickReplies.push({ label: zone, type: "location", value: zone });
    });
  } else if (!lead.readyForMatching) {
    getScenarioReplies(lead).forEach((note) => {
      quickReplies.push({ label: note, type: "note", value: note });
    });
  }

  if (lead.readyForMatching) {
    quickReplies.push({ label: "Buscar opciones", type: "match" });
  }

  return quickReplies.slice(0, 6);
}

function inferStage(lead) {
  if (!lead) return "idle";
  if (lead.readyForMatching) return "ready";
  if ((lead.blockingFields || []).includes("zona")) return "location";
  return "details";
}

function buildTurnFocus(lead) {
  if (!lead) {
    return "Cuéntame qué pasó.";
  }

  if (lead.readyForMatching) {
    return "Revisar las opciones que preparé para tu caso.";
  }

  const blockingFields = lead.blockingFields || [];
  if (blockingFields.includes("zona")) {
    return "Dime tu zona para ubicar el caso.";
  }

  const category = (lead.detectedCategory || "").toLowerCase();
  if (category === "electricidad") {
    return "Dime si es sin luz total, salta la llave o afecta solo una parte.";
  }
  if (category === "plomeria") {
    return "Dime si pierde agua, está tapado o no sale agua.";
  }
  if (category === "cerrajeria") {
    return "Dime si no puedes entrar, la llave se rompió o la puerta quedó trabada.";
  }

  return "Dame un detalle más para afinar el caso.";
}

function buildAgentMessage(lead) {
  if (!lead) {
    return "Cuéntame qué pasó y te voy guiando paso a paso.";
  }

  if (lead.readyForMatching) {
    return "Perfecto. Ya tengo lo suficiente como para empezar a moverte opciones concretas, sin hacerte seguir completando cosas innecesarias.";
  }

  const blockingFields = lead.blockingFields || [];
  if (blockingFields.includes("zona")) {
    return "Ya entendí bastante bien el problema. Para decirte a quién conviene mandar y ver disponibilidad real, decime tu zona.";
  }

  if (blockingFields.includes("categoria")) {
    return "Tengo una idea del caso, pero necesito afinar bien qué tipo de ayuda necesitas para no hacerte perder tiempo.";
  }

  const category = (lead.detectedCategory || "").toLowerCase();
  if (category === "electricidad") {
    return "Bien. Ahora quiero entender la gravedad: ¿te quedaste sin luz total, salta la llave o es solo en una parte?";
  }
  if (category === "plomeria") {
    return "Bien. Para priorizarlo mejor, dime si pierde agua, está tapado o directamente te está frenando el uso de algo importante.";
  }
  if (category === "cerrajeria") {
    return "Entiendo. Para ayudarte rápido, dime si no puedes entrar, si la llave se rompió o si la puerta quedó trabada.";
  }

  const missing = (lead.missingFields || []).map(formatField).slice(0, 2).join(" y ");
  if (missing) {
    return `Voy bien. Si quieres acelerar todavía más, ayúdame con ${missing}.`;
  }

  return "Estoy terminando de acomodar tu caso para avanzar con la mejor siguiente acción.";
}

function updateQuickReplies(lead) {
  const wrapper = document.getElementById("quick-replies");
  if (!wrapper) return;

  const quickReplies = getQuickReplies(lead);
  wrapper.innerHTML = "";

  if (!quickReplies.length) {
    wrapper.hidden = true;
    return;
  }

  quickReplies.forEach((reply) => {
    const button = document.createElement("button");
    button.type = "button";
    button.className = "quick-reply-chip";
    button.textContent = reply.label;
    button.dataset.type = reply.type;
    if (reply.value) {
      button.dataset.value = reply.value;
    }
    wrapper.appendChild(button);
  });

  wrapper.hidden = false;
}

function updateAgentPanel(lead) {
  state.lead = lead;

  const badge = document.getElementById("agent-status-badge");
  const title = document.getElementById("agent-title");
  const summary = document.getElementById("agent-summary");
  const nextAction = document.getElementById("agent-next-action");
  const missingFields = document.getElementById("agent-missing-fields");
  const understood = document.getElementById("agent-understood");
  const confidence = document.getElementById("agent-confidence");
  const contextForm = document.getElementById("context-form");
  const matchButton = document.getElementById("match-button");
  const turnFocusText = document.getElementById("turn-focus-text");
  const progressFill = document.getElementById("agent-progress-fill");
  const progressText = document.getElementById("agent-progress-text");
  const messageText = document.getElementById("agent-message-text");
  const locationField = document.getElementById("location-field");
  const notesField = document.getElementById("notes-field");
  const contextSubmitButton = document.getElementById("context-submit-button");

  if (!badge || !title || !summary || !nextAction || !missingFields || !understood || !confidence || !contextForm || !matchButton || !turnFocusText || !progressFill || !progressText || !messageText || !locationField || !notesField || !contextSubmitButton) {
    return;
  }

  const blockingFields = lead.blockingFields || [];
  const missing = lead.missingFields || [];
  const progress = getProgressValue(lead);
  const agentMessage = buildAgentMessage(lead);
  state.stage = inferStage(lead);

  badge.textContent = lead.readyForMatching ? "Listo para buscar" : state.stage === "location" ? "Ubicando el caso" : "Tomando tu caso";
  title.textContent = lead.problem || "Caso iniciado";
  summary.textContent = lead.summary || "Estoy interpretando tu caso.";
  nextAction.textContent = humanizeNextAction(lead.nextRecommendedAction);
  missingFields.textContent = missing.length ? missing.map(formatField).join(", ") : "Nada crítico";
  understood.textContent = [lead.detectedCategory, lead.urgency, lead.location].filter(Boolean).join(" · ") || "Ya tengo una primera lectura";
  confidence.textContent = lead.readyForMatching
    ? "Listo para recomendarte"
    : state.stage === "location"
      ? "Ubicando el caso"
      : "Afinando el caso";
  turnFocusText.textContent = buildTurnFocus(lead);
  progressFill.style.width = `${progress}%`;
  progressText.textContent = lead.readyForMatching
    ? "Paso 3 de 3: caso listo para mostrar opciones"
    : blockingFields.includes("zona")
      ? "Paso 2 de 3: ubicar el caso"
      : "Paso 2 de 3: entender mejor el detalle";

  const lastEntry = state.transcript[state.transcript.length - 1];
  if (!lastEntry || lastEntry.role !== "agent" || lastEntry.text !== agentMessage) {
    pushTranscript("agent", agentMessage);
  } else {
    renderTranscript();
  }
  messageText.textContent = agentMessage;

  contextForm.hidden = false;
  matchButton.disabled = !lead.readyForMatching;
  matchButton.hidden = !lead.readyForMatching;
  locationField.hidden = !blockingFields.includes("zona") && lead.readyForMatching;
  notesField.hidden = lead.readyForMatching;
  contextSubmitButton.textContent = lead.readyForMatching ? "Actualizar detalles" : "Responder esto";

  updateQuickReplies(lead);
}

function renderMatches(payload) {
  const wrapper = document.getElementById("match-results");
  const list = document.getElementById("match-list");
  if (!wrapper || !list) return;

  list.innerHTML = "";
  wrapper.hidden = false;

  if (!payload.matches || payload.matches.length === 0) {
    const empty = document.createElement("div");
    empty.className = "match-card match-card-empty";

    const title = document.createElement("strong");
    title.textContent = "Todavía no encontré una opción clara";

    const nextAction = document.createElement("p");
    nextAction.textContent = humanizeNextAction(payload.nextRecommendedAction);

    empty.append(title, nextAction);
    list.appendChild(empty);
    return;
  }

  const recommendationSummary = document.getElementById("recommendation-summary");
  if (recommendationSummary) {
    recommendationSummary.textContent = `Encontré ${payload.matches.length} opcion(es) ordenadas por encaje con tu caso y tu zona.`;
  }

  payload.matches.forEach((match, index) => {
    const item = document.createElement("article");
    item.className = "match-card";

    const head = document.createElement("div");
    head.className = "match-card-head";

    const title = document.createElement("strong");
    title.textContent = `${index === 0 ? "Mejor encaje" : `Opción ${index + 1}`} · ${match.name}`;

    const score = document.createElement("span");
    score.className = "match-score";
    score.textContent = `Score ${match.score}`;

    head.append(title, score);

    const recommendation = document.createElement("p");
    recommendation.className = "match-recommendation-line";
    recommendation.textContent = index === 0
      ? "Si quieres ir por la opción más alineada con tu caso, empezaría por esta."
      : "Buena alternativa para comparar precio, rapidez o disponibilidad.";

    item.append(
      head,
      recommendation,
      buildMatchDetail("Rubro:", match.category),
      buildMatchDetail("Zona:", match.zone),
      buildMatchDetail("Teléfono:", match.phone),
      buildMatchDetail("Por qué te la recomiendo:", (match.reasons || []).join(", ")),
    );
    list.appendChild(item);
  });
}

function buildMatchDetail(label, value) {
  const line = document.createElement("p");
  const strong = document.createElement("strong");
  strong.textContent = label;
  line.append(strong, ` ${value || ""}`);
  return line;
}

async function createLead(payload) {
  const response = await fetch(`${API_BASE_URL}/api/public/leads`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.error?.message || "No pude iniciar el caso");
  }
  return data;
}

async function createProviderLead(payload) {
  const response = await fetch(`${API_BASE_URL}/api/public/providers`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.error?.message || "No pude registrar el perfil del proveedor");
  }
  return data;
}

async function updateLeadContext(payload) {
  const response = await fetch(`${API_BASE_URL}/api/public/leads/${state.leadId}/context`, {
    method: "PATCH",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify(payload),
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.error?.message || "No pude actualizar el contexto");
  }
  return data;
}

async function generateMatches() {
  const response = await fetch(`${API_BASE_URL}/api/public/leads/${state.leadId}/matches`, {
    method: "POST",
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data?.error?.message || "No pude buscar opciones");
  }
  return data;
}

async function submitContext(payload) {
  const lead = await updateLeadContext(payload);
  updateAgentPanel(lead);
  showLeadFormFeedback(
    lead.readyForMatching
      ? "Perfecto. Ya tengo lo necesario para empezar a mostrarte opciones."
      : buildAgentMessage(lead),
    false,
  );
  return lead;
}

function syncModeUI() {
  const providerFlowCard = document.getElementById("provider-flow-card");
  const turnFocusCard = document.getElementById("turn-focus-card");
  const providerButton = document.getElementById("provider-mode-button");
  const problemLabel = document.querySelector('.hero-problem-label span');
  const problemInput = document.querySelector('#lead-form textarea[name="problem"]');

  if (providerFlowCard) {
    providerFlowCard.hidden = state.mode !== "provider";
  }
  if (turnFocusCard) {
    turnFocusCard.hidden = state.mode === "provider" && !state.leadId;
  }
  if (providerButton) {
    providerButton.textContent = state.mode === "provider" ? "Modo proveedor activo" : "Soy proveedor";
  }
  if (problemLabel) {
    problemLabel.textContent = state.mode === "provider" ? "Cuéntanos sobre tu servicio" : "¿Qué está pasando?";
  }
  if (problemInput) {
    problemInput.placeholder = state.mode === "provider"
      ? "Ej: soy electricista, trabajo en Pocitos y Cordón, hago urgencias 24h y tengo 8 años de experiencia"
      : "Ej: me quedé sin luz y necesito resolverlo ya";
  }
}

function setupLeadForm() {
  const form = document.getElementById("lead-form");
  if (!form) return;

  form.addEventListener("submit", async (event) => {
    event.preventDefault();

    const formData = new FormData(form);
    const payload = {
      name: formData.get("name")?.toString().trim() || null,
      phone: formData.get("phone")?.toString().trim() || null,
      problem: formData.get("problem")?.toString().trim() || "",
      channel: "web",
    };

    if (!payload.problem) {
      showLeadFormFeedback("Cuéntame el problema para que pueda empezar.", true);
      return;
    }

    try {
      state.transcript = [];
      pushTranscript("user", payload.problem);

      if (state.mode === "provider") {
        const provider = await createProviderLead({
          name: payload.name,
          phone: payload.phone,
          message: payload.problem,
          channel: "web-provider",
        });
        document.getElementById("match-results")?.setAttribute("hidden", "hidden");
        showLeadFormFeedback(
          provider.readyForReview
            ? "Perfecto. Fixy dejó tu perfil listo para revisión interna."
            : `Fixy ya tomó tu perfil. Aún falta: ${provider.missingFields?.join(", ") || "algunos datos"}.`,
          false,
        );
        const transcriptText = provider.suggestedReply || "Ya entendí tu perfil inicial como proveedor.";
        pushTranscript("agent", transcriptText);
        const understood = document.getElementById("agent-understood");
        const next = document.getElementById("agent-next-action");
        const confidence = document.getElementById("agent-confidence");
        const missing = document.getElementById("agent-missing-fields");
        const turnFocus = document.getElementById("turn-focus-text");
        if (understood) understood.textContent = [provider.category, provider.zone].filter(Boolean).join(" · ") || "Perfil proveedor";
        if (next) next.textContent = provider.nextRecommendedAction === "ready_for_review" ? "Listo para revisión" : "Completar perfil";
        if (confidence) confidence.textContent = provider.readyForReview ? "Perfil estructurado" : "Perfil en construcción";
        if (missing) missing.textContent = provider.missingFields?.length ? provider.missingFields.join(", ") : "Nada crítico";
        if (turnFocus) turnFocus.textContent = provider.readyForReview ? "Esperar revisión interna de Fixy." : `Completar: ${provider.missingFields?.join(", ") || "datos faltantes"}.`;
        document.getElementById("agent-panel")?.scrollIntoView({ behavior: "smooth", block: "start" });
        return;
      }

      const lead = await createLead(payload);
      state.leadId = lead.id;
      updateAgentPanel(lead);
      document.getElementById("match-results")?.setAttribute("hidden", "hidden");
      showLeadFormFeedback("Ya tomé tu caso. Ahora te voy a guiar como si estuvieras hablando con un operador bueno: rápido y sin pedirte de más.", false);
      document.getElementById("agent-panel")?.scrollIntoView({ behavior: "smooth", block: "start" });
    } catch (error) {
      showLeadFormFeedback(error.message || "No pude iniciar el caso ahora mismo.", true);
    }
  });
}

function setupContextForm() {
  const form = document.getElementById("context-form");
  if (!form) return;

  form.addEventListener("submit", async (event) => {
    event.preventDefault();

    if (!state.leadId) {
      showLeadFormFeedback("Primero cuéntame el problema para abrir el caso.", true);
      return;
    }

    const formData = new FormData(form);
    const payload = {
      location: formData.get("location")?.toString().trim() || null,
      notes: formData.get("notes")?.toString().trim() || null,
    };

    if (!payload.location && !payload.notes) {
      showLeadFormFeedback("Dame al menos la zona o un detalle más para seguir.", true);
      return;
    }

    try {
      const userParts = [payload.location, payload.notes].filter(Boolean);
      if (userParts.length) {
        pushTranscript("user", userParts.join(" · "));
      }
      await submitContext(payload);
    } catch (error) {
      showLeadFormFeedback(error.message || "No pude actualizar el contexto.", true);
    }
  });
}

function setupQuickReplies() {
  const wrapper = document.getElementById("quick-replies");
  const locationInput = document.querySelector('#context-form input[name="location"]');
  const notesInput = document.querySelector('#context-form textarea[name="notes"]');
  const matchButton = document.getElementById("match-button");

  if (!wrapper || !locationInput || !notesInput || !matchButton) return;

  wrapper.addEventListener("click", async (event) => {
    const button = event.target.closest("button.quick-reply-chip");
    if (!button) return;

    const type = button.dataset.type;
    const value = button.dataset.value || "";

    try {
      if (type === "location") {
        locationInput.value = value;
        pushTranscript("user", value);
        await submitContext({ location: value, notes: null });
      } else if (type === "note") {
        notesInput.value = notesInput.value ? `${notesInput.value}\n${value}` : value;
        pushTranscript("user", value);
        await submitContext({ location: null, notes: value });
      } else if (type === "match") {
        matchButton.click();
      }
    } catch (error) {
      showLeadFormFeedback(error.message || "No pude aplicar esa respuesta rápida.", true);
    }
  });
}

function setupMatchButton() {
  const button = document.getElementById("match-button");
  if (!button) return;

  button.addEventListener("click", async () => {
    if (!state.leadId) {
      showLeadFormFeedback("Primero inicia un caso para que pueda buscar opciones.", true);
      return;
    }

    try {
      const payload = await generateMatches();
      updateAgentPanel(payload.lead);
      pushTranscript("agent", payload.matches?.length
        ? `Ya encontré ${payload.matches.length} opción(es) que encajan con tu caso. Te las ordeno para que elijas más fácil.`
        : "Todavía no tengo una opción clara. Necesito un poco más de contexto para recomendarte bien.");
      renderMatches(payload);
      showLeadFormFeedback(
        payload.matches?.length
          ? "Ya encontré opciones que encajan con tu caso. Ahora puedes comparar y elegir con más contexto."
          : "Todavía no tengo una opción clara. Conviene sumar un poco más de contexto o ampliar la búsqueda.",
        false,
      );
    } catch (error) {
      showLeadFormFeedback(error.message || "No pude buscar opciones.", true);
    }
  });
}

function setupProviderModeButton() {
  const button = document.getElementById("provider-mode-button");
  if (!button) return;

  button.addEventListener("click", () => {
    state.mode = state.mode === "provider" ? "client" : "provider";
    state.leadId = null;
    state.transcript = [];
    renderTranscript();
    syncModeUI();
    showLeadFormFeedback(
      state.mode === "provider"
        ? "Modo proveedor activado. Cuéntale a Fixy tu rubro, zonas y experiencia."
        : "Modo cliente activado. Cuéntale a Fixy qué problema necesitas resolver.",
      false,
    );
  });
}

document.addEventListener("DOMContentLoaded", () => {
  setCurrentYear();
  syncModeUI();
  setupProviderModeButton();
  setupLeadForm();
  setupContextForm();
  setupQuickReplies();
  setupMatchButton();
});
