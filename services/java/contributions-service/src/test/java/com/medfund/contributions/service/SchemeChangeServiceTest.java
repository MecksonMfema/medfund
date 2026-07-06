package com.medfund.contributions.service;

import com.medfund.contributions.dto.SchemeChangeRequest;
import com.medfund.contributions.entity.CurrencyChangeWaitingPeriodRule;
import com.medfund.contributions.entity.Scheme;
import com.medfund.contributions.entity.SchemeChange;
import com.medfund.contributions.entity.SchemeChangeWaitingPeriodRule;
import com.medfund.contributions.repository.CurrencyChangeWaitingPeriodRuleRepository;
import com.medfund.contributions.repository.SchemeChangeRepository;
import com.medfund.contributions.repository.SchemeChangeWaitingPeriodRuleRepository;
import com.medfund.contributions.repository.SchemeRepository;
import com.medfund.shared.audit.AuditPublisher;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.r2dbc.core.DatabaseClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Guards the V048/V049 gap-closing behaviour of SchemeChangeService:
 *
 * <ul>
 *   <li>Classifies CURRENCY_CHANGE vs CROSS_GRADE by comparing scheme
 *       currency codes.
 *   <li>Applies direction-specific currency waiting rules (V049) —
 *       ZWG→USD auto-pushes but USD→ZWG stays on the requested date.
 *   <li>Back-dated request bypasses PENDING and publishes SCHEME_CHANGED
 *       with backdated=true.
 *   <li>Forward-dated request stays PENDING; no scheme flip and no event.
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class SchemeChangeServiceTest {

    @Mock SchemeChangeRepository schemeChangeRepository;
    @Mock SchemeRepository schemeRepository;
    @Mock SchemeChangeWaitingPeriodRuleRepository waitingPeriodRuleRepository;
    @Mock CurrencyChangeWaitingPeriodRuleRepository currencyWaitingRuleRepository;
    @Mock AuditPublisher auditPublisher;
    @Mock ContributionEventPublisher eventPublisher;
    @Mock DatabaseClient db;

    private SchemeChangeService service;

    @BeforeEach
    void setUp() {
        service = new SchemeChangeService(schemeChangeRepository, schemeRepository,
                waitingPeriodRuleRepository, currencyWaitingRuleRepository,
                auditPublisher, eventPublisher, db);
        lenient().when(auditPublisher.publish(any())).thenReturn(Mono.empty());
        lenient().when(eventPublisher.publishSchemeChanged(any())).thenReturn(Mono.empty());
        // Default: no upgrade/downgrade rules — request tests without
        // explicit stubs get the "no wait" path.
        lenient().when(waitingPeriodRuleRepository.findAllOrdered()).thenReturn(Flux.empty());
    }

    // ------------------------------------------------------------------
    // request — forward-dated → PENDING
    // ------------------------------------------------------------------

    @Test
    void request_forwardDated_stashesPending_doesNotFlipScheme_doesNotPublishEvent() {
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1).plusMonths(2);

        stubSchemes(fromScheme, "USD", toScheme, "USD");
        // No currency waiting rules — same-currency change short-circuits.
        stubSchemeChangeSave();

        var req = new SchemeChangeRequest(memberId, fromScheme, toScheme, effective, "moving up", null);

        StepVerifier.create(service.request(req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("PENDING");
                    assertThat(saved.getChangeKind()).isEqualTo("CROSS_GRADE");
                    assertThat(saved.getEffectiveDate()).isEqualTo(effective);
                })
                .verifyComplete();

        // Members row's scheme_id is NOT flipped yet — makeEffective
        // does that after approval.
        verify(db, never()).sql(anyString());
        // Forward-dated → no SCHEME_CHANGED event fires now.
        verify(eventPublisher, never()).publishSchemeChanged(any());
    }

    // ------------------------------------------------------------------
    // request — back-dated → EFFECTIVE with backdated=true
    // ------------------------------------------------------------------

    @Test
    void request_backDated_appliesImmediately_flipsScheme_publishesBackdatedEvent() {
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1).minusMonths(2);

        stubSchemes(fromScheme, "USD", toScheme, "USD");
        stubSchemeChangeSave();
        stubMembersUpdate(1L);

        var req = new SchemeChangeRequest(memberId, fromScheme, toScheme, effective, "correction", null);

        StepVerifier.create(service.request(req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("EFFECTIVE");
                    assertThat(saved.getChangeKind()).isEqualTo("CROSS_GRADE");
                })
                .verifyComplete();

        // Members row got updated with the new scheme.
        verify(db).sql(anyString());
        // SCHEME_CHANGED event fires with backdated=true.
        ArgumentCaptor<ContributionEventPublisher.SchemeChangedPayload> captor =
                ArgumentCaptor.forClass(ContributionEventPublisher.SchemeChangedPayload.class);
        verify(eventPublisher).publishSchemeChanged(captor.capture());
        assertThat(captor.getValue().backdated()).isTrue();
    }

    // ------------------------------------------------------------------
    // Classification — CURRENCY_CHANGE vs CROSS_GRADE
    // ------------------------------------------------------------------

    @Test
    void request_crossCurrency_classifiesAsCurrencyChange() {
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1).plusMonths(1);

        stubSchemes(fromScheme, "ZWG", toScheme, "USD");
        // No matching direction rule → no auto-push.
        when(currencyWaitingRuleRepository.findActive(eq("ZWG"), eq("USD"))).thenReturn(Mono.empty());
        stubSchemeChangeSave();

        var req = new SchemeChangeRequest(memberId, fromScheme, toScheme, effective, "swap currency", null);

        StepVerifier.create(service.request(req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getChangeKind()).isEqualTo("CURRENCY_CHANGE");
                    assertThat(saved.getEffectiveDate()).isEqualTo(effective);
                })
                .verifyComplete();
    }

    @Test
    void request_sameCurrency_classifiesAsCrossGrade() {
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate effective = LocalDate.now().withDayOfMonth(1).plusMonths(1);

        stubSchemes(fromScheme, "USD", toScheme, "USD");
        stubSchemeChangeSave();

        var req = new SchemeChangeRequest(memberId, fromScheme, toScheme, effective, "same-currency", null);

        StepVerifier.create(service.request(req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> assertThat(saved.getChangeKind()).isEqualTo("CROSS_GRADE"))
                .verifyComplete();

        // Same-currency change → currency waiting lookup is skipped.
        verify(currencyWaitingRuleRepository, never()).findActive(anyString(), anyString());
    }

    // ------------------------------------------------------------------
    // V049 — direction-specific currency waiting periods
    // ------------------------------------------------------------------

    @Test
    void request_zwgToUsd_withWaitingRule_pushesEffectiveDateForward() {
        // Direction ZWG → USD has a 90-day rule → effective_date is
        // pushed to today + 90 days, snapped to 1st of month.
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        // Requester asks for 1st of NEXT month, but the 90-day wait
        // is more restrictive → gets pushed further out.
        LocalDate requested = LocalDate.now().withDayOfMonth(1).plusMonths(1);
        LocalDate expected = LocalDate.now().plusDays(90).withDayOfMonth(1);

        stubSchemes(fromScheme, "ZWG", toScheme, "USD");
        CurrencyChangeWaitingPeriodRule rule = new CurrencyChangeWaitingPeriodRule();
        rule.setFromCurrency("ZWG");
        rule.setToCurrency("USD");
        rule.setWaitingDays(90);
        rule.setIsActive(true);
        when(currencyWaitingRuleRepository.findActive(eq("ZWG"), eq("USD")))
                .thenReturn(Mono.just(rule));
        stubSchemeChangeSave();

        var req = new SchemeChangeRequest(memberId, fromScheme, toScheme, requested, "moving to USD", null);

        StepVerifier.create(service.request(req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getChangeKind()).isEqualTo("CURRENCY_CHANGE");
                    assertThat(saved.getEffectiveDate())
                            .as("ZWG→USD 90-day wait pushes effective_date to at least today+90 (1st of month)")
                            .isEqualTo(expected);
                })
                .verifyComplete();
    }

    @Test
    void request_usdToZwg_withNoReverseRule_keepsRequestedDate() {
        // The reverse direction has no rule (asymmetric waits) →
        // effective_date must stay on the requester's original.
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate requested = LocalDate.now().withDayOfMonth(1).plusMonths(1);

        stubSchemes(fromScheme, "USD", toScheme, "ZWG");
        // Direction USD → ZWG: no active rule.
        when(currencyWaitingRuleRepository.findActive(eq("USD"), eq("ZWG"))).thenReturn(Mono.empty());
        stubSchemeChangeSave();

        var req = new SchemeChangeRequest(memberId, fromScheme, toScheme, requested, "moving to ZWG", null);

        StepVerifier.create(service.request(req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getChangeKind()).isEqualTo("CURRENCY_CHANGE");
                    assertThat(saved.getEffectiveDate())
                            .as("no reverse-direction rule → no wait, requested date stands")
                            .isEqualTo(requested);
                })
                .verifyComplete();
    }

    @Test
    void request_upgradeWithWaitingRule_pushesEffectiveDateForward() {
        // Two UPGRADE rules (30d, 60d) → MAX(60) wins.
        UUID memberId = UUID.randomUUID();
        UUID fromScheme = UUID.randomUUID();
        UUID toScheme = UUID.randomUUID();
        LocalDate requested = LocalDate.now().withDayOfMonth(1);
        LocalDate expected = LocalDate.now().plusDays(60).withDayOfMonth(1);

        stubSchemes(fromScheme, "USD", toScheme, "USD");
        SchemeChangeWaitingPeriodRule r30 = new SchemeChangeWaitingPeriodRule();
        r30.setChangeType("UPGRADE");
        r30.setWaitingDays(30);
        r30.setIsActive(true);
        SchemeChangeWaitingPeriodRule r60 = new SchemeChangeWaitingPeriodRule();
        r60.setChangeType("UPGRADE");
        r60.setWaitingDays(60);
        r60.setIsActive(true);
        when(waitingPeriodRuleRepository.findAllOrdered()).thenReturn(Flux.just(r30, r60));
        stubSchemeChangeSave();

        // Explicit UPGRADE override via changeKind — same-currency
        // schemes classify as CROSS_GRADE by default.
        var req = new SchemeChangeRequest(memberId, fromScheme, toScheme, requested, "upgrading", "UPGRADE");

        StepVerifier.create(service.request(req, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getChangeKind()).isEqualTo("UPGRADE");
                    assertThat(saved.getEffectiveDate())
                            .as("MAX of 30/60 = 60 days → today+60 (1st of month)")
                            .isEqualTo(expected);
                })
                .verifyComplete();
    }

    // ------------------------------------------------------------------
    // approve / reject / makeEffective sanity
    // ------------------------------------------------------------------

    @Test
    void approve_flipsToApproved() {
        UUID id = UUID.randomUUID();
        UUID actor = UUID.randomUUID();
        SchemeChange row = new SchemeChange();
        row.setId(id);
        row.setMemberId(UUID.randomUUID());
        row.setFromSchemeId(UUID.randomUUID());
        row.setToSchemeId(UUID.randomUUID());
        row.setStatus("PENDING");

        when(schemeChangeRepository.findById(id)).thenReturn(Mono.just(row));
        when(schemeChangeRepository.save(any(SchemeChange.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

        StepVerifier.create(service.approve(id, actor.toString(), "boss@medfund.com"))
                .assertNext(saved -> {
                    assertThat(saved.getStatus()).isEqualTo("APPROVED");
                    assertThat(saved.getApprovedBy()).isEqualTo(actor);
                    assertThat(saved.getApprovedAt()).isNotNull();
                })
                .verifyComplete();
    }

    @Test
    void makeEffective_appliesToMember_publishesEventWithBackdatedFalse() {
        UUID id = UUID.randomUUID();
        SchemeChange row = new SchemeChange();
        row.setId(id);
        row.setMemberId(UUID.randomUUID());
        row.setFromSchemeId(UUID.randomUUID());
        row.setToSchemeId(UUID.randomUUID());
        row.setStatus("APPROVED");
        row.setEffectiveDate(LocalDate.now().withDayOfMonth(1));
        row.setChangeKind("UPGRADE");

        when(schemeChangeRepository.findById(id)).thenReturn(Mono.just(row));
        when(schemeChangeRepository.save(any(SchemeChange.class)))
                .thenAnswer(inv -> Mono.just(inv.getArgument(0)));
        stubMembersUpdate(1L);

        StepVerifier.create(service.makeEffective(id, UUID.randomUUID().toString(), "op@medfund.com"))
                .assertNext(saved -> assertThat(saved.getStatus()).isEqualTo("EFFECTIVE"))
                .verifyComplete();

        ArgumentCaptor<ContributionEventPublisher.SchemeChangedPayload> captor =
                ArgumentCaptor.forClass(ContributionEventPublisher.SchemeChangedPayload.class);
        verify(eventPublisher).publishSchemeChanged(captor.capture());
        assertThat(captor.getValue().backdated()).isFalse();
        assertThat(captor.getValue().changeKind()).isEqualTo("UPGRADE");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private void stubSchemes(UUID fromId, String fromCurrency, UUID toId, String toCurrency) {
        Scheme from = new Scheme();
        from.setId(fromId);
        from.setCurrencyCode(fromCurrency);
        Scheme to = new Scheme();
        to.setId(toId);
        to.setCurrencyCode(toCurrency);
        lenient().when(schemeRepository.findById(fromId)).thenReturn(Mono.just(from));
        lenient().when(schemeRepository.findById(toId)).thenReturn(Mono.just(to));
    }

    private void stubSchemeChangeSave() {
        lenient().when(schemeChangeRepository.save(any(SchemeChange.class)))
                .thenAnswer(inv -> {
                    SchemeChange row = inv.getArgument(0);
                    if (row.getId() == null) row.setId(UUID.randomUUID());
                    return Mono.just(row);
                });
    }

    private void stubMembersUpdate(long rows) {
        DatabaseClient.GenericExecuteSpec spec = mock(DatabaseClient.GenericExecuteSpec.class);
        org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> fetch =
                mockFetchSpec();
        lenient().when(db.sql(anyString())).thenReturn(spec);
        lenient().when(spec.bind(anyString(), any())).thenReturn(spec);
        lenient().when(spec.fetch()).thenReturn(fetch);
        lenient().when(fetch.rowsUpdated()).thenReturn(Mono.just(rows));
    }

    @SuppressWarnings("unchecked")
    private static org.springframework.r2dbc.core.FetchSpec<java.util.Map<String, Object>> mockFetchSpec() {
        return mock(org.springframework.r2dbc.core.FetchSpec.class);
    }
}
