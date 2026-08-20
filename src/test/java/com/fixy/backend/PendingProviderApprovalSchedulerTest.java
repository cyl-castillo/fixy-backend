package com.fixy.backend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import com.fixy.backend.model.Lead;
import com.fixy.backend.model.LeadStatus;
import com.fixy.backend.model.Provider;
import com.fixy.backend.model.ProviderStatus;
import com.fixy.backend.repository.LeadRepository;
import com.fixy.backend.repository.ProviderRepository;
import com.fixy.backend.service.PendingProviderApprovalScheduler;
import com.fixy.backend.service.ProviderCatalogService;
import com.fixy.backend.service.TelegramNotifyService;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * Dato de prod (2026-08-19): el proveedor #16 de mandados lleva 13 días en
 * NEW mientras 11 de los 19 pedidos abiertos son de mandados y el chat les
 * contesta que no hay nadie libre. {@link PendingProviderApprovalScheduler}
 * le pone número al costo y se lo recuerda a ops hasta que haya decisión.
 *
 * Aserciones sobre el proveedor PUNTUAL del test, nunca sobre el tamaño del
 * digest: este contexto comparte H2 con otras clases de las mismas
 * properties y cualquiera puede dejar proveedores pendientes sueltos
 * (memoria "interferencia entre contextos").
 */
@SpringBootTest
class PendingProviderApprovalSchedulerTest {

  private static final long MIN_HOURS = 12;
  private static final long REMIND_EVERY_HOURS = 24;

  @Autowired private ProviderRepository providerRepository;
  @Autowired private LeadRepository leadRepository;
  @Autowired private ProviderCatalogService providerCatalogService;

  private PendingProviderApprovalScheduler scheduler(TelegramNotifyService telegram, Clock clock) {
    return new PendingProviderApprovalScheduler(
        providerRepository, leadRepository, providerCatalogService, telegram,
        true, MIN_HOURS, REMIND_EVERY_HOURS, clock);
  }

  /** Reloj corrido: los createdAt recién escritos no se pueden retro-datar. */
  private Clock inHours(long hours) {
    return Clock.fixed(Instant.now().plus(Duration.ofHours(hours)), ZoneOffset.UTC);
  }

  private Provider saveProvider(String name, String zone, ProviderStatus status, String sourceType) {
    Provider provider = new Provider();
    provider.setName(name);
    provider.setPhone("099" + Math.abs(name.hashCode() % 1000000));
    provider.setCategories("mandados");
    provider.setPrimaryZone(zone);
    provider.setStatus(status);
    provider.setSourceType(sourceType);
    provider.setAccessToken(UUID.randomUUID().toString().replace("-", ""));
    return providerRepository.save(provider);
  }

  private Lead saveOpenLead(String category, String zone) {
    Lead lead = new Lead();
    lead.setProblem("Pedido de prueba de aprobación pendiente");
    lead.setDetectedCategory(category);
    lead.setLocation(zone);
    lead.setReadyForMatching(true);
    lead.setStatus(LeadStatus.NEW);
    return leadRepository.save(lead);
  }

  private List<TelegramNotifyService.PendingApproval> captureDigest(TelegramNotifyService telegram) {
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<TelegramNotifyService.PendingApproval>> captor =
        ArgumentCaptor.forClass(List.class);
    verify(telegram).notifyPendingProviderApprovals(captor.capture(), anyInt());
    return captor.getValue();
  }

  private Optional<TelegramNotifyService.PendingApproval> rowFor(
      List<TelegramNotifyService.PendingApproval> digest, Provider provider) {
    return digest.stream()
        .filter(row -> row.provider().getId().equals(provider.getId()))
        .findFirst();
  }

  @Test
  void avisaDelPendienteConLosPedidosQueEstaDejandoPasar() {
    String zone = "Zona Aprobacion Uno";
    Provider pending = saveProvider("Mandadero Sin Aprobar", zone, ProviderStatus.NEW, "admin_panel");
    saveOpenLead("mandados", zone);
    saveOpenLead("mandados", zone);
    saveOpenLead("plomeria", zone); // otra categoría: no es demanda suya

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    scheduler(telegram, inHours(49)).processOnce();

    TelegramNotifyService.PendingApproval row = rowFor(captureDigest(telegram), pending).orElseThrow();
    assertThat(row.openLeads()).isEqualTo(2);
    assertThat(row.daysWaiting()).isEqualTo(2);
  }

  @Test
  void avisaAunqueTodaviaNoHayaDemandaEnSuCategoria() {
    Provider pending = saveProvider(
        "Mandadero Sin Demanda", "Zona Aprobacion Dos", ProviderStatus.NEW, "admin_panel");

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    scheduler(telegram, inHours(13)).processOnce();

    TelegramNotifyService.PendingApproval row = rowFor(captureDigest(telegram), pending).orElseThrow();
    assertThat(row.openLeads()).isZero();
  }

  @Test
  void elAutoregistradoInactivoCuentaComoPendiente() {
    Provider selfRegistered = saveProvider(
        "Autoregistrado Esperando", "Zona Aprobacion Tres", ProviderStatus.INACTIVE, "autoregistro");

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    scheduler(telegram, inHours(13)).processOnce();

    assertThat(rowFor(captureDigest(telegram), selfRegistered)).isPresent();
  }

  @Test
  void elInactivoCargadoAManoEsUnaBajaDecididaYNoSeReclama() {
    Provider deactivated = saveProvider(
        "Baja Deliberada", "Zona Aprobacion Cuatro", ProviderStatus.INACTIVE, "manual");
    // Un pendiente cualquiera para que el digest exista igual.
    saveProvider("Pendiente Acompanante", "Zona Aprobacion Cuatro", ProviderStatus.NEW, "admin_panel");

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    scheduler(telegram, inHours(13)).processOnce();

    assertThat(rowFor(captureDigest(telegram), deactivated)).isEmpty();
  }

  @Test
  void elProveedorYaActivoNoAparece() {
    Provider active = saveProvider(
        "Mandadero Activo", "Zona Aprobacion Cinco", ProviderStatus.AVAILABLE, "autoregistro");
    saveProvider("Pendiente Testigo", "Zona Aprobacion Cinco", ProviderStatus.NEW, "admin_panel");

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    scheduler(telegram, inHours(13)).processOnce();

    assertThat(rowFor(captureDigest(telegram), active)).isEmpty();
  }

  @Test
  void noReclamaAlRecienRegistradoQueYaDisparoSuPropioAviso() {
    Provider justRegistered = saveProvider(
        "Recien Llegado", "Zona Aprobacion Seis", ProviderStatus.NEW, "autoregistro");

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    // Sin correr el reloj: lleva ~0h esperando, por debajo del mínimo de 12h.
    scheduler(telegram, Clock.systemUTC()).processOnce();

    List<TelegramNotifyService.PendingApproval> digest = digestOrEmpty(telegram);
    assertThat(rowFor(digest, justRegistered)).isEmpty();
  }

  @Test
  void ignoraProveedoresDeHumo() {
    Provider smoke = saveProvider(
        "[smoke] Pausa Test", "Zona Aprobacion Siete", ProviderStatus.NEW, "manual");
    saveProvider("Pendiente Real", "Zona Aprobacion Siete", ProviderStatus.NEW, "admin_panel");

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    scheduler(telegram, inHours(13)).processOnce();

    assertThat(rowFor(captureDigest(telegram), smoke)).isEmpty();
  }

  @Test
  void unaSegundaCorridaDentroDeLaVentanaNoRepiteElAviso() {
    saveProvider("Pendiente Repetido", "Zona Aprobacion Ocho", ProviderStatus.NEW, "admin_panel");

    TelegramNotifyService telegram = mock(TelegramNotifyService.class);
    PendingProviderApprovalScheduler scheduler = scheduler(telegram, inHours(13));

    assertThat(scheduler.processOnce()).isPositive();
    // Mismo reloj (misma "hora"): la ventana de 24h todavía no se cumplió.
    assertThat(scheduler.processOnce()).isZero();
    verify(telegram).notifyPendingProviderApprovals(org.mockito.ArgumentMatchers.anyList(), anyInt());
  }

  /** Todas las filas notificadas (vacío si no hubo digest) — sin exigir que lo haya. */
  @SuppressWarnings("unchecked")
  private List<TelegramNotifyService.PendingApproval> digestOrEmpty(TelegramNotifyService telegram) {
    return org.mockito.Mockito.mockingDetails(telegram).getInvocations().stream()
        .filter(invocation -> "notifyPendingProviderApprovals".equals(invocation.getMethod().getName()))
        .flatMap(invocation ->
            ((List<TelegramNotifyService.PendingApproval>) invocation.getArgument(0)).stream())
        .toList();
  }
}
