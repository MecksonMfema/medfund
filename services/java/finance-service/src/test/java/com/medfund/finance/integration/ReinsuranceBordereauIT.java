package com.medfund.finance.integration;

import com.medfund.finance.reinsurance.dto.CessionBordereauRow;
import com.medfund.finance.reinsurance.dto.ClaimAdjudicatedEvent;
import com.medfund.finance.reinsurance.dto.CreateReinsurerRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyApplicableLineRequest;
import com.medfund.finance.reinsurance.dto.CreateTreatyRequest;
import com.medfund.finance.reinsurance.dto.RecoveriesBordereauRow;
import com.medfund.finance.reinsurance.dto.TreatyUtilizationRow;
import com.medfund.finance.reinsurance.dto.UpsertTreatyParticipantRequest;
import com.medfund.finance.reinsurance.entity.BordereauPeriodExport;
import com.medfund.finance.reinsurance.entity.Cession;
import com.medfund.finance.reinsurance.entity.Recovery;
import com.medfund.finance.reinsurance.repository.BordereauPeriodExportRepository;
import com.medfund.finance.reinsurance.repository.CessionRepository;
import com.medfund.finance.reinsurance.repository.RecoveryRepository;
import com.medfund.finance.reinsurance.service.BordereauPeriodExportService;
import com.medfund.finance.reinsurance.service.BordereauReportService;
import com.medfund.finance.reinsurance.service.BordereauReportWorkbookService;
import com.medfund.finance.reinsurance.service.CessionService;
import com.medfund.finance.reinsurance.service.ReinsurerService;
import com.medfund.finance.reinsurance.service.TreatyApplicableLineService;
import com.medfund.finance.reinsurance.service.TreatyParticipantService;
import com.medfund.finance.reinsurance.service.TreatyService;
import com.medfund.rules.fact.RuleResult;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportKey;
import com.medfund.shared.report.ReportResponse;
import com.medfund.shared.testfixtures.AbstractIntegrationTest;
import com.medfund.shared.testfixtures.TenantTestContext;
import com.medfund.shared.testfixtures.WithTenant;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.test.context.TestPropertySource;
import reactor.core.publisher.Mono;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 4 bordereau reports. Real Postgres
 * (Testcontainers) + real R2DBC repositories + real service layer. The
 * rules-engine seam is mocked so we can drive canned cession rule results
 * without authoring live DRL for a synthetic tenant (same shape as
 * {@link ReinsuranceLossCessionIT}). AuditPublisher is mocked because
 * there's no broker fixture in this class.
 *
 * <p>Covers:
 * <ul>
 *   <li>{@code BordereauReportService.cessionBordereau} returns envelope
 *       with participant-split rows + per-currency subtotals</li>
 *   <li>{@code BordereauReportWorkbookService.cessionWorkbook} renders a
 *       valid XLSX with per-currency sheets + Summary</li>
 *   <li>{@code BordereauPeriodExportService.markExported} inserts on first
 *       call, increments export_count on second</li>
 *   <li>{@code isPriorPeriodAdjustment} flips to TRUE for cessions written
 *       after the first export of the quarter</li>
 *   <li>{@code recoveries} query returns rows joined on the cession/participant
 *       fan-out</li>
 *   <li>{@code utilization} query returns per-layer totals grouped by
 *       ceded currency</li>
 *   <li>Recovery bulk EXPECTED→INVOICED transition (Phase 4 §5 / R8)</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(ReinsuranceBordereauIT.SecurityStub.class)
class ReinsuranceBordereauIT extends AbstractIntegrationTest {

    @TestConfiguration
    static class SecurityStub {
        @Bean
        ReactiveJwtDecoder reactiveJwtDecoder() {
            return token -> Mono.just(new Jwt(
                    token, Instant.now(), Instant.now().plusSeconds(300),
                    Map.of("alg", "none"),
                    Map.of("sub", "test", "iss", "test")));
        }
    }

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000012";
    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    @Autowired private BordereauReportService reportService;
    @Autowired private BordereauReportWorkbookService workbookService;
    @Autowired private BordereauPeriodExportService periodExportService;
    @Autowired private BordereauPeriodExportRepository periodExportRepository;
    @Autowired private CessionService cessionService;
    @Autowired private CessionRepository cessionRepository;
    @Autowired private RecoveryRepository recoveryRepository;
    @Autowired private ReinsurerService reinsurerService;
    @Autowired private TreatyService treatyService;
    @Autowired private TreatyParticipantService participantService;
    @Autowired private TreatyApplicableLineService applicableLineService;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    @Test
    @WithTenant(TENANT_ID)
    void cessionBordereau_returnsParticipantSplitRowsInEnvelope() {
        Fixture fx = seedTreatyWithTwoParticipants();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        seedCession(fx.treatyId, "USD", "1000.00", "300.00", occurredAt);

        int year = occurredAt.getYear();
        int quarter = (occurredAt.getMonthValue() - 1) / 3 + 1;
        ReportResponse<List<CessionBordereauRow>> env = reportService
                .cessionBordereau(null, fx.treatyId, year, quarter, "USD")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        assertThat(env).isNotNull();
        assertThat(env.reportKey()).isEqualTo(ReportKey.REINSURANCE_CESSION_BORDEREAU.name());
        assertThat(env.reportingCurrency()).isEqualTo("USD");
        // Two participants → 2 rows for one cession.
        assertThat(env.data()).hasSize(2);
        BigDecimal participantSum = env.data().stream()
                .map(CessionBordereauRow::participantCeded)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(participantSum).isEqualByComparingTo("300.00");
        // Per-currency aggregate matches the participant-split sum.
        assertThat(env.perCurrency()).containsKey("USD");
        assertThat(env.perCurrency().get("USD").totalAmount())
                .isEqualByComparingTo("300.00");
        assertThat(env.perCurrency().get("USD").rowCount()).isEqualTo(2L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void cessionWorkbook_writesValidXlsxWithPerCurrencySheetPlusSummary() throws Exception {
        Fixture fx = seedTreatyWithTwoParticipants();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        seedCession(fx.treatyId, "USD", "1000.00", "300.00", occurredAt);

        int year = occurredAt.getYear();
        int quarter = (occurredAt.getMonthValue() - 1) / 3 + 1;
        byte[] bytes = workbookService
                .cessionWorkbook(null, fx.treatyId, year, quarter, "USD",
                        UUID.fromString(TENANT_ID))
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(bytes).isNotNull();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheet("Cessions USD")).isNotNull();
            assertThat(wb.getSheet("Summary")).isNotNull();
        }
    }

    @Test
    @WithTenant(TENANT_ID)
    void markExported_firstCallInserts_secondCallIncrementsExportCount() {
        Fixture fx = seedTreatyWithTwoParticipants();

        BordereauPeriodExport first = periodExportService
                .markExported(fx.reinsurerOneId, fx.treatyId,
                        ReportKey.REINSURANCE_CESSION_BORDEREAU.name(), 2026, 1,
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(first).isNotNull();
        assertThat(first.getExportCount()).isEqualTo(1);

        BordereauPeriodExport second = periodExportService
                .markExported(fx.reinsurerOneId, fx.treatyId,
                        ReportKey.REINSURANCE_CESSION_BORDEREAU.name(), 2026, 1,
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(second).isNotNull();
        assertThat(second.getExportCount()).isEqualTo(2);
        // Postgres round-trips TIMESTAMPTZ at microsecond precision; Java's OffsetDateTime
        // is nanosecond. Compare with a 1s tolerance — "same underlying instant" is what
        // we care about here, not the last three digits.
        assertThat(second.getFirstExportedAt())
                .isCloseTo(first.getFirstExportedAt(),
                        within(1, java.time.temporal.ChronoUnit.SECONDS));
    }

    @Test
    @WithTenant(TENANT_ID)
    void newCessionAfterFirstExport_flagsPriorPeriodAdjustment() throws Exception {
        Fixture fx = seedTreatyWithTwoParticipants();
        // Establish an "already exported" quarter by inserting the registry row first.
        // firstExportedAt now → then create a cession afterwards (createdAt > firstExportedAt).
        periodExportService.markExported(fx.reinsurerOneId, fx.treatyId,
                        ReportKey.REINSURANCE_CESSION_BORDEREAU.name(),
                        LocalDate.now().getYear(), currentQuarter(),
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        Thread.sleep(50);  // ensure createdAt is strictly after firstExportedAt

        OffsetDateTime occurredAt = OffsetDateTime.now();
        seedCession(fx.treatyId, "USD", "1000.00", "300.00", occurredAt);

        // The lookup keys on (reinsurerId, treatyId, reportKey, year, quarter).
        // Query with the SAME reinsurer we exported for so the isPriorPeriodAdjustment
        // check finds our recorded firstExportedAt.
        ReportResponse<List<CessionBordereauRow>> env = reportService
                .cessionBordereau(fx.reinsurerOneId, fx.treatyId,
                        occurredAt.getYear(), currentQuarter(), null)
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(env).isNotNull();
        assertThat(env.data()).allSatisfy(r ->
                assertThat(r.priorPeriodAdjustment()).as("participant row should be flagged").isTrue());
    }

    @Test
    @WithTenant(TENANT_ID)
    void recoveriesBordereau_joinsRecoveryAndCessionAndParticipant() {
        Fixture fx = seedTreatyWithTwoParticipants();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        UUID cessionId = seedCession(fx.treatyId, "USD", "1000.00", "300.00", occurredAt);
        seedRecovery(cessionId, "300.00", "USD");

        ReportResponse<List<RecoveriesBordereauRow>> env = reportService
                .recoveriesBordereau(null, fx.treatyId,
                        occurredAt.getYear(), currentQuarter(), null)
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(env).isNotNull();
        assertThat(env.data()).hasSize(2);
        assertThat(env.data()).allSatisfy(r -> {
            assertThat(r.status()).isEqualTo("EXPECTED");
            assertThat(r.currencyCode()).isEqualTo("USD");
        });
    }

    @Test
    @WithTenant(TENANT_ID)
    void treatyUtilization_returnsSinceInceptionCededTotalsPerCurrency() {
        Fixture fx = seedTreatyWithTwoParticipants();
        OffsetDateTime occurredAt = OffsetDateTime.now();
        seedCession(fx.treatyId, "USD", "1000.00", "300.00", occurredAt);
        seedCession(fx.treatyId, "USD", "2000.00", "600.00", occurredAt);

        ReportResponse<List<TreatyUtilizationRow>> env = reportService
                .treatyUtilization(fx.treatyId, LocalDate.now(), null)
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(env).isNotNull();
        assertThat(env.data()).isNotEmpty();
        BigDecimal totalCeded = env.data().stream()
                .map(TreatyUtilizationRow::totalCededNative)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        assertThat(totalCeded).isEqualByComparingTo("900.00");
    }

    @Test
    @WithTenant(TENANT_ID)
    void markInvoicedByCessionIds_flipsExpectedToInvoiced() {
        Fixture fx = seedTreatyWithTwoParticipants();
        UUID cessionOne = seedCession(fx.treatyId, "USD", "1000.00", "300.00", OffsetDateTime.now());
        UUID cessionTwo = seedCession(fx.treatyId, "USD", "500.00", "150.00", OffsetDateTime.now());
        seedRecovery(cessionOne, "300.00", "USD");
        seedRecovery(cessionTwo, "150.00", "USD");

        Long updated = recoveryRepository
                .markInvoicedByCessionIds(List.of(cessionOne, cessionTwo), OffsetDateTime.now())
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(updated).isEqualTo(2L);

        Recovery r1 = recoveryRepository.findByCessionId(cessionOne)
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(r1).isNotNull();
        assertThat(r1.getStatus()).isEqualTo("INVOICED");
        assertThat(r1.getInvoicedAt()).isNotNull();
    }

    // ── Fixture helpers ─────────────────────────────────────────────────────

    private static final class Fixture {
        UUID reinsurerOneId;
        UUID reinsurerTwoId;
        UUID treatyId;
    }

    private Fixture seedTreatyWithTwoParticipants() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("REINSURANCE"), any()))
                .thenReturn(Mono.just(List.of()));

        Fixture fx = new Fixture();
        var r1 = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "Munich Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        var r2 = reinsurerService.create(
                        new CreateReinsurerRequest(
                                "Swiss Re " + UUID.randomUUID(), null, null, null, null, null),
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(r1).isNotNull();
        assertThat(r2).isNotNull();
        fx.reinsurerOneId = r1.id();
        fx.reinsurerTwoId = r2.id();

        var draft = treatyService.createDraft(
                        new CreateTreatyRequest(
                                "HEALTH-QS-BORD-" + UUID.randomUUID(), "QUOTA_SHARE", "USD",
                                LocalDate.now().minusDays(30), LocalDate.now().plusDays(300),
                                null, null, null, null),
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(draft).isNotNull();
        fx.treatyId = draft.id();

        participantService.upsert(fx.treatyId,
                        new UpsertTreatyParticipantRequest(fx.reinsurerOneId,
                                new BigDecimal("60.0000"), "LEADER"),
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        participantService.upsert(fx.treatyId,
                        new UpsertTreatyParticipantRequest(fx.reinsurerTwoId,
                                new BigDecimal("40.0000"), "FOLLOWING"),
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        applicableLineService.add(fx.treatyId,
                        new CreateTreatyApplicableLineRequest("HEALTH"),
                        "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        treatyService.activate(fx.treatyId, "sys", "sys@medfund")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);

        return fx;
    }

    /** Inserts a raw {@link Cession} row via the repository — no rules-engine round-trip. */
    private UUID seedCession(UUID treatyId, String currency, String basis, String ceded,
                             OffsetDateTime occurredAt) {
        Cession c = new Cession();
        c.setTreatyId(treatyId);
        c.setCessionType("LOSS");
        c.setSource("AUTOMATIC");
        c.setStatus("ACTIVE");
        c.setSourceEventId(UUID.randomUUID());
        c.setSourceEventType("CLAIM_ADJUDICATED");
        c.setBasisAmount(new BigDecimal(basis));
        c.setCededAmount(new BigDecimal(ceded));
        c.setCurrencyCode(currency);
        c.setOccurredAt(occurredAt);
        c.setActorId(UUID.randomUUID());
        c.setActorEmail("sys@medfund");
        Cession saved = cessionRepository.save(c)
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(saved).isNotNull();
        return saved.getId();
    }

    private void seedRecovery(UUID cessionId, String expected, String currency) {
        Recovery r = new Recovery();
        r.setCessionId(cessionId);
        r.setStatus("EXPECTED");
        r.setExpectedAmount(new BigDecimal(expected));
        r.setCurrencyCode(currency);
        r.setActorId(UUID.randomUUID());
        r.setActorEmail("sys@medfund");
        Recovery saved = recoveryRepository.save(r)
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(saved).isNotNull();
    }

    private static int currentQuarter() {
        return (LocalDate.now().getMonthValue() - 1) / 3 + 1;
    }

    // Suppress unused-field warning for the imports required by the fixture.
    @SuppressWarnings("unused")
    private void _unusedSuppressor(ClaimAdjudicatedEvent e, RuleResult r) { }
}
