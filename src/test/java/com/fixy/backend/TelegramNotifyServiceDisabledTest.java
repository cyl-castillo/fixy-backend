package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;

import com.fixy.backend.dto.ProviderCatalogItem;
import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.service.TelegramNotifyService;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Sin credenciales (TELEGRAM_BOT_TOKEN / TELEGRAM_CHAT_ID vacíos, valor por
 * defecto de application.yml y del entorno de test): el servicio queda
 * disabled y notifyOpportunityWithMatches/notifyDemandWithoutSupply son
 * no-ops seguros. No hace falta mock de red porque el primer chequeo de
 * shouldNotify() ya corta antes de intentar el POST.
 */
@SpringBootTest
class TelegramNotifyServiceDisabledTest {

  @Autowired
  private TelegramNotifyService telegramNotifyService;

  @Autowired
  private LeadRepository leadRepository;

  @Test
  void disabledByDefault_withoutCredentials() {
    assertThat(telegramNotifyService.isEnabled()).isFalse();
  }

  @Test
  void withoutCredentials_doesNotThrowAndDoesNothing() {
    Lead lead = new Lead();
    lead.setName("Cliente Test");
    lead.setPhone("099111222");
    lead.setProblem("Se me rompió la canilla");
    lead.setChannel("chat");
    lead.setDetectedCategory("plomeria");
    lead.setLocation("Solymar");
    lead.setUrgency("media");
    lead.setMissingFields("");
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    lead.setNotes("");
    lead.setHistory("test");
    lead.setAccessToken("tok-disabled-test");
    Lead saved = leadRepository.save(lead);

    List<ProviderCatalogItem> matches = List.of(
        new ProviderCatalogItem(1L, "Juan Plomero", "plomeria", "Solymar", "099888777", "AVAILABLE", "manual"));

    // No debe lanzar excepción ni intentar red real: el flujo de negocio
    // (creación/matching del lead) sigue intacto sin Telegram configurado.
    telegramNotifyService.notifyOpportunityWithMatches(saved, matches);
    telegramNotifyService.notifyDemandWithoutSupply(saved);
  }
}
