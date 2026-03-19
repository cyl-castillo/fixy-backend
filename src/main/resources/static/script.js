const WHATSAPP_NUMBER = "59893551242";

const MESSAGES = {
  customer: "Hola, necesito ayuda con un problema en casa.",
  provider: "Hola, quiero unirme como proveedor a Fixy.",
};

function buildWhatsAppLink(number, text) {
  const cleanNumber = String(number).replace(/\D/g, "");
  const encodedMessage = encodeURIComponent(text);
  return `https://wa.me/${cleanNumber}?text=${encodedMessage}`;
}

function setupWhatsAppButtons() {
  const elements = document.querySelectorAll("[data-wa]");

  elements.forEach((element) => {
    const key = element.getAttribute("data-wa");
    const message = MESSAGES[key] || MESSAGES.customer;
    element.setAttribute("href", buildWhatsAppLink(WHATSAPP_NUMBER, message));
    element.setAttribute("target", "_blank");
    element.setAttribute("rel", "noopener noreferrer");
  });
}

function setCurrentYear() {
  const yearElement = document.getElementById("year");
  if (yearElement) {
    yearElement.textContent = new Date().getFullYear();
  }
}

document.addEventListener("DOMContentLoaded", () => {
  setupWhatsAppButtons();
  setCurrentYear();
});
