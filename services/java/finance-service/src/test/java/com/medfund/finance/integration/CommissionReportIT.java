package com.medfund.finance.integration;

import com.medfund.finance.producer.dto.AssignMemberRequest;
import com.medfund.finance.producer.dto.ClawbackRegisterRow;
import com.medfund.finance.producer.dto.CommissionStatementRow;
import com.medfund.finance.producer.dto.ContributionPaidEvent;
import com.medfund.finance.producer.dto.CreateProducerRequest;
import com.medfund.finance.producer.dto.CreateRateCardRequest;
import com.medfund.finance.producer.dto.ProducerResponse;
import com.medfund.finance.producer.service.CommissionCalcService;
import com.medfund.finance.producer.service.CommissionClawbackReportService;
import com.medfund.finance.producer.service.CommissionClawbackService;
import com.medfund.finance.producer.service.CommissionRateCardService;
import com.medfund.finance.producer.service.CommissionStatementService;
import com.medfund.finance.producer.service.CommissionWorkbookService;
import com.medfund.finance.producer.service.MemberProducerAssignmentService;
import com.medfund.finance.producer.service.ProducerService;
import com.medfund.rules.service.RuleEvaluationService;
import com.medfund.rules.service.TenantRuleLoader;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.report.ReportEnablementReader;
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
import org.springframework.r2dbc.core.DatabaseClient;
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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * End-to-end integration for the Phase 11 §A Phase 4 commission reports.
 * Real Postgres (Testcontainers) + real R2DBC + real services. Rules engine
 * mocked to keep the harness focused on the report-envelope + XLSX render
 * path (DRL compilation is exercised elsewhere).
 *
 * <p>Covers:
 * <ul>
 *   <li>Statement envelope carries native rows + per-currency subtotals,
 *       and the tenant-default reporting currency lands in the response.</li>
 *   <li>Statement XLSX renders bytes that parse back into a valid workbook
 *       with the expected sheets.</li>
 *   <li>Producer filter narrows the envelope to a single producer.</li>
 *   <li>Clawback register envelope + optional {@code source} filter.</li>
 *   <li>Disabling the report via {@code public.tenant_report_config} makes
 *       {@link ReportEnablementReader#isEnabled(ReportKey)} return false —
 *       {@link com.medfund.shared.report.ReportGuardAspect} would then
 *       short-circuit the controller with 403 (asserted at the reader level
 *       so no HTTP layer is required for the assertion).</li>
 * </ul>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@TestPropertySource(properties = {
        "spring.flyway.locations=classpath:db/test-migration",
        "spring.flyway.baseline-on-migrate=true"
})
@Import(CommissionReportIT.SecurityStub.class)
class CommissionReportIT extends AbstractIntegrationTest {

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

    private static final String TENANT_ID = "00000000-0000-4000-8000-000000000043";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static final LocalDate PERIOD_START = LocalDate.now().withDayOfMonth(1).minusMonths(1);
    private static final LocalDate PERIOD_END   = LocalDate.now().plusDays(1);

    @Autowired private ProducerService producerService;
    @Autowired private CommissionRateCardService rateCardService;
    @Autowired private MemberProducerAssignmentService assignmentService;
    @Autowired private CommissionCalcService commissionCalcService;
    @Autowired private CommissionClawbackService commissionClawbackService;
    @Autowired private CommissionStatementService statementService;
    @Autowired private CommissionClawbackReportService clawbackReportService;
    @Autowired private CommissionWorkbookService workbookService;
    @Autowired private ReportEnablementReader reportEnablementReader;
    @Autowired private DatabaseClient databaseClient;

    @MockBean private AuditPublisher auditPublisher;
    @MockBean private RuleEvaluationService ruleEvaluationService;
    @MockBean private TenantRuleLoader tenantRuleLoader;

    // ── Statement envelope ──────────────────────────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void statement_returnsNativeRowsWithPerCurrencySubtotals() {
        stubRulesAndAudit();
        UUID memberId = UUID.randomUUID();
        UUID producerId = seedProducerAndAssign(memberId, "USD");
        seedRateCard("HEALTH", new BigDecimal("10.0000"));
        accrueCommission(memberId, "500.00", "USD", "HEALTH");

        // Filter to the specific producer we seeded so pre-existing sibling
        // test rows in this shared test schema don't invalidate size + total.
        ReportResponse<List<CommissionStatementRow>> env = statementService
                .statement(PERIOD_START, PERIOD_END, producerId, "USD")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        assertThat(env).isNotNull();
        assertThat(env.reportKey()).isEqualTo(ReportKey.COMMISSION_STATEMENT.name());
        assertThat(env.reportingCurrency()).isEqualTo("USD");
        assertThat(env.data()).hasSize(1);
        CommissionStatementRow row = env.data().get(0);
        assertThat(row.producerId()).isEqualTo(producerId);
        assertThat(row.nativeAmount()).isEqualByComparingTo("50.0000");
        assertThat(row.nativeCurrency()).isEqualTo("USD");
        assertThat(row.status()).isEqualTo("ACCRUED");
        assertThat(env.perCurrency()).containsKey("USD");
        assertThat(env.perCurrency().get("USD").totalAmount()).isEqualByComparingTo("50.0000");
        assertThat(env.perCurrency().get("USD").rowCount()).isEqualTo(1L);
    }

    @Test
    @WithTenant(TENANT_ID)
    void statementXlsx_rendersValidWorkbookWithCurrencySheetPlusSummary() throws Exception {
        stubRulesAndAudit();
        UUID memberId = UUID.randomUUID();
        seedProducerAndAssign(memberId, "USD");
        seedRateCard("HEALTH", new BigDecimal("10.0000"));
        accrueCommission(memberId, "500.00", "USD", "HEALTH");

        byte[] bytes = workbookService
                .statementWorkbook(PERIOD_START, PERIOD_END, null, "USD",
                        UUID.fromString(TENANT_ID))
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(bytes).isNotNull();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheet("Statement USD")).isNotNull();
            assertThat(wb.getSheet("Summary")).isNotNull();
        }
    }

    @Test
    @WithTenant(TENANT_ID)
    void statement_producerFilter_narrowsToSingleProducer() {
        stubRulesAndAudit();
        UUID memberA = UUID.randomUUID();
        UUID producerA = seedProducerAndAssign(memberA, "USD");
        seedRateCard("HEALTH", new BigDecimal("10.0000"));
        accrueCommission(memberA, "500.00", "USD", "HEALTH");

        UUID memberB = UUID.randomUUID();
        UUID producerB = seedProducerAndAssign(memberB, "USD");
        accrueCommission(memberB, "700.00", "USD", "HEALTH");

        ReportResponse<List<CommissionStatementRow>> narrowed = statementService
                .statement(PERIOD_START, PERIOD_END, producerA, "USD")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(narrowed).isNotNull();
        // Producer filter is scoped by producerId only, so pre-existing test rows
        // for OTHER producers stay out. Filtering to producerA yields exactly the
        // one accrual we made against memberA.
        assertThat(narrowed.data()).hasSize(1);
        assertThat(narrowed.data()).allSatisfy(r ->
                assertThat(r.producerId()).isEqualTo(producerA));
        assertThat(narrowed.perCurrency().get("USD").rowCount()).isEqualTo(1L);

        // The unfiltered call also sees prior-test rows in this shared test
        // schema; assert both new producers are present rather than count-based.
        ReportResponse<List<CommissionStatementRow>> both = statementService
                .statement(PERIOD_START, PERIOD_END, null, "USD")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(both).isNotNull();
        assertThat(both.data()).extracting(CommissionStatementRow::producerId)
                .contains(producerA, producerB);
    }

    // ── Clawback register envelope ──────────────────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void clawbackRegister_returnsCompensatingRowsWithSourceFilter() {
        stubRulesAndAudit();
        UUID memberId = UUID.randomUUID();
        UUID producerId = seedProducerAndAssign(memberId, "USD");
        seedRateCard("HEALTH", new BigDecimal("10.0000"), 90);
        UUID contributionId = UUID.randomUUID();
        accrueCommission(memberId, contributionId, "500.00", "USD", "HEALTH");

        // Trigger a CONTRIBUTION_REVOKE clawback path.
        commissionClawbackService
                .processContributionRevoke(contributionId, memberId, Instant.now(),
                        "IT reversal", "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        // Narrow by producerId so pre-existing sibling-test rows can't
        // contaminate size-based assertions in the shared test schema.
        ReportResponse<List<ClawbackRegisterRow>> env = clawbackReportService
                .register(PERIOD_START, PERIOD_END, producerId, null, "USD")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(env).isNotNull();
        assertThat(env.data()).hasSize(1);
        assertThat(env.data()).allSatisfy(r -> {
            assertThat(r.producerId()).isEqualTo(producerId);
            assertThat(r.source()).isEqualTo("CONTRIBUTION_REVOKE");
            assertThat(r.nativeCurrency()).isEqualTo("USD");
            assertThat(r.commissionReference()).startsWith("COMM-");
        });

        // Source filter that matches the row keeps it.
        ReportResponse<List<ClawbackRegisterRow>> kept = clawbackReportService
                .register(PERIOD_START, PERIOD_END, producerId, "CONTRIBUTION_REVOKE", "USD")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(kept.data()).hasSize(1);

        // Source filter that doesn't match filters everything out (for
        // this specific producer, no MEMBER_LAPSE row was ever written).
        ReportResponse<List<ClawbackRegisterRow>> filteredOut = clawbackReportService
                .register(PERIOD_START, PERIOD_END, producerId, "MEMBER_LAPSE", "USD")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(filteredOut.data()).isEmpty();
    }

    @Test
    @WithTenant(TENANT_ID)
    void clawbackXlsx_rendersOneSheetPerSource() throws Exception {
        stubRulesAndAudit();
        UUID memberId = UUID.randomUUID();
        seedProducerAndAssign(memberId, "USD");
        seedRateCard("HEALTH", new BigDecimal("10.0000"), 90);
        UUID contributionId = UUID.randomUUID();
        accrueCommission(memberId, contributionId, "500.00", "USD", "HEALTH");
        commissionClawbackService
                .processContributionRevoke(contributionId, memberId, Instant.now(),
                        "IT reversal", "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);

        byte[] bytes = workbookService
                .clawbackWorkbook(PERIOD_START, PERIOD_END, null, null, "USD",
                        UUID.fromString(TENANT_ID))
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
        assertThat(bytes).isNotNull();
        try (Workbook wb = new XSSFWorkbook(new ByteArrayInputStream(bytes))) {
            assertThat(wb.getSheet("Clawback CONTRIBUTION_REVOKE")).isNotNull();
            assertThat(wb.getSheet("Summary")).isNotNull();
        }
    }

    // ── @RequiresReport gate ────────────────────────────────────────────────

    @Test
    @WithTenant(TENANT_ID)
    void reportEnablement_disabledConfig_shortCircuits() {
        // Baseline: default enabled (no config row).
        Boolean enabledBefore = reportEnablementReader
                .isEnabled(UUID.fromString(TENANT_ID), ReportKey.COMMISSION_STATEMENT)
                .block(TIMEOUT);
        assertThat(enabledBefore).isTrue();

        databaseClient.sql("""
                INSERT INTO public.tenant_report_config
                       (id, tenant_id, report_key, enabled, updated_at)
                VALUES (gen_random_uuid(), :tid, :key, FALSE, NOW())
                    ON CONFLICT (tenant_id, report_key) DO UPDATE
                       SET enabled = FALSE, updated_at = NOW()
                """)
                .bind("tid", UUID.fromString(TENANT_ID))
                .bind("key", ReportKey.COMMISSION_STATEMENT.name())
                .fetch().rowsUpdated().block(TIMEOUT);

        Boolean enabledAfter = reportEnablementReader
                .isEnabled(UUID.fromString(TENANT_ID), ReportKey.COMMISSION_STATEMENT)
                .block(TIMEOUT);
        assertThat(enabledAfter).isFalse();

        // Cleanup so ordering-agnostic sibling tests see enabled=TRUE.
        databaseClient.sql("""
                DELETE FROM public.tenant_report_config
                 WHERE tenant_id = :tid AND report_key = :key
                """)
                .bind("tid", UUID.fromString(TENANT_ID))
                .bind("key", ReportKey.COMMISSION_STATEMENT.name())
                .fetch().rowsUpdated().block(TIMEOUT);
    }

    // ── Seed helpers ────────────────────────────────────────────────────────

    private void stubRulesAndAudit() {
        when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        when(tenantRuleLoader.ensureLoaded(any())).thenReturn(Mono.empty());
        when(ruleEvaluationService.evaluateInGroup(eq(TENANT_ID), eq("COMMISSION"), any()))
                .thenReturn(Mono.just(List.of()));
    }

    private UUID seedProducerAndAssign(UUID memberId, String homeCurrency) {
        ProducerResponse producer = producerService.create(
                        new CreateProducerRequest(
                                "BRK-" + UUID.randomUUID().toString().substring(0, 8),
                                "Test Broker " + memberId.toString().substring(0, 6),
                                null, null, "ZW", homeCurrency,
                                null, null, null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        assertThat(producer).isNotNull();
        assignmentService.assign(memberId,
                        new AssignMemberRequest(producer.id(),
                                LocalDate.now().withDayOfMonth(1), "test"),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
        return producer.id();
    }

    private void seedRateCard(String insuranceLine, BigDecimal ratePct) {
        seedRateCard(insuranceLine, ratePct, null);
    }

    private void seedRateCard(String insuranceLine, BigDecimal ratePct, Integer clawbackWindowDays) {
        rateCardService.create(
                        new CreateRateCardRequest(
                                "Rate " + insuranceLine + " " + ratePct + " " + UUID.randomUUID(),
                                insuranceLine, null, ratePct, clawbackWindowDays,
                                LocalDate.now().minusMonths(3), null),
                        UUID.randomUUID().toString(), "admin@test.example")
                .contextWrite(TenantTestContext.put()).block(TIMEOUT);
    }

    private void accrueCommission(UUID memberId, String amount, String currency, String insuranceLine) {
        accrueCommission(memberId, UUID.randomUUID(), amount, currency, insuranceLine);
    }

    private void accrueCommission(UUID memberId, UUID contributionId, String amount,
                                   String currency, String insuranceLine) {
        ContributionPaidEvent event = new ContributionPaidEvent(
                contributionId, memberId,
                new BigDecimal(amount), currency, insuranceLine,
                OffsetDateTime.now(), TENANT_ID);
        commissionCalcService
                .processPaidContribution(event, "sys", "system@medfund")
                .contextWrite(TenantTestContext.put())
                .block(TIMEOUT);
    }
}
