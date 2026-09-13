package com.medfund.finance.regulatory.pmb;

import com.medfund.finance.client.ClaimsClient;
import com.medfund.finance.client.UserServiceClient;
import com.medfund.finance.client.UserServiceClient.PmbBeneficiaryCountResponse;
import com.medfund.finance.dto.PmbPaidAggregateRow;
import com.medfund.finance.regulatory.pmb.PmbSchemeIdentityReader.SchemeIdentity;
import com.medfund.finance.regulatory.service.RegulatoryFxPolicy;
import com.medfund.shared.report.CrossServiceCallHelper;
import com.medfund.shared.report.ReportKey;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Concrete {@link PmbSpendRawDataProvider}. Composes three peer feeds into
 * a single {@link PmbSpendRawData}:
 *
 * <ol>
 *   <li>claims-service {@code /aggregate/pmb-paid} — per
 *       ({@code isPmb}, {@code pmbConditionCode}, {@code currencyCode})
 *       paid totals + counts</li>
 *   <li>user-service {@code /reports/pmb/beneficiary-count} — principal
 *       members + dependants active on the reporting period end</li>
 *   <li>{@link PmbSchemeIdentityReader} — scheme name + registration
 *       number for the report's meta section</li>
 * </ol>
 *
 * <p>Non-ZAR paid amounts are converted through {@link RegulatoryFxPolicy}
 * with a fail-loud contract: a missing FX rate aborts the report per
 * regulator invariant, not a silent zero.
 *
 * <p>{@code @Primary} demotes {@link StubPmbSpendRawDataProvider} at scan
 * time. Both beans remain for tests and for a controlled rollback path.
 */
@Slf4j
@Component
@Primary
@RequiredArgsConstructor
public class RealPmbSpendRawDataProvider implements PmbSpendRawDataProvider {

    static final String ZAR = "ZAR";

    private final ClaimsClient claimsClient;
    private final UserServiceClient userServiceClient;
    private final RegulatoryFxPolicy fxPolicy;
    private final PmbSchemeIdentityReader schemeIdentityReader;

    @Override
    public Mono<PmbSpendRawData> load(UUID tenantId, LocalDate periodStart, LocalDate periodEnd) {
        List<String> warnings = new ArrayList<>();

        Mono<List<PmbPaidAggregateRow>> paidRows = CrossServiceCallHelper.guarded(
                "pmb-paid-aggregate",
                claimsClient.pmbPaid(periodStart, periodEnd),
                List.<PmbPaidAggregateRow>of(),
                warnings);

        Mono<PmbBeneficiaryCountResponse> beneficiaries = CrossServiceCallHelper.guarded(
                "pmb-beneficiary-count",
                userServiceClient.pmbBeneficiaryCount(periodEnd),
                new PmbBeneficiaryCountResponse(0L, 0L, 0L),
                warnings);

        Mono<SchemeIdentity> identity = schemeIdentityReader.load(tenantId);

        return Mono.zip(paidRows, beneficiaries, identity)
                .flatMap(t -> assemble(t.getT1(), t.getT2(), t.getT3(),
                        tenantId, periodStart, periodEnd, warnings));
    }

    /**
     * Fold the flat aggregate rows into category buckets, converting every
     * non-ZAR total through {@link RegulatoryFxPolicy}. A single missing
     * rate fails the whole report, which is the point.
     */
    private Mono<PmbSpendRawData> assemble(List<PmbPaidAggregateRow> paidRows,
                                           PmbBeneficiaryCountResponse beneficiaries,
                                           SchemeIdentity identity,
                                           UUID tenantId,
                                           LocalDate periodStart,
                                           LocalDate periodEnd,
                                           List<String> warnings) {
        for (String w : warnings) {
            log.warn("[pmb-provider] tenant={} period={}..{}: {}", tenantId, periodStart, periodEnd, w);
        }
        return Flux.fromIterable(paidRows)
                .flatMap(row -> convertToZar(row, tenantId, periodEnd))
                .collectList()
                .map(converted -> bucketise(converted, identity, beneficiaries));
    }

    private Mono<PmbPaidAggregateRow> convertToZar(PmbPaidAggregateRow row,
                                                   UUID tenantId,
                                                   LocalDate asOf) {
        String currency = row.currencyCode();
        if (currency == null || ZAR.equalsIgnoreCase(currency)) {
            return Mono.just(row);
        }
        return fxPolicy.convert(ReportKey.PMB_SPEND, tenantId, asOf,
                        row.paidAmount(), currency, ZAR)
                .map(zar -> new PmbPaidAggregateRow(
                        row.isPmb(),
                        row.pmbConditionCode(),
                        ZAR,
                        zar,
                        row.claimCount()));
    }

    /**
     * Categorise each ZAR-converted row into a PMB bucket (or non-PMB) and
     * sum by category. Unknown / null condition codes on PMB rows fall to
     * {@link PmbCategory#OTHER} so an unclassifiable claim still contributes
     * to the totals.
     */
    private PmbSpendRawData bucketise(List<PmbPaidAggregateRow> zarRows,
                                      SchemeIdentity identity,
                                      PmbBeneficiaryCountResponse beneficiaries) {
        Map<PmbCategory, BigDecimal> paidByCategory = new EnumMap<>(PmbCategory.class);
        Map<PmbCategory, Long> countByCategory = new EnumMap<>(PmbCategory.class);
        for (PmbCategory c : PmbCategory.values()) {
            paidByCategory.put(c, BigDecimal.ZERO);
            countByCategory.put(c, 0L);
        }
        BigDecimal nonPmbPaid = BigDecimal.ZERO;

        for (PmbPaidAggregateRow row : zarRows) {
            BigDecimal amount = row.paidAmount() != null ? row.paidAmount() : BigDecimal.ZERO;
            long count = row.claimCount();
            if (Boolean.TRUE.equals(row.isPmb())) {
                PmbCategory category = PmbCategory.forCode(row.pmbConditionCode());
                paidByCategory.merge(category, amount, BigDecimal::add);
                countByCategory.merge(category, count, Long::sum);
            } else {
                nonPmbPaid = nonPmbPaid.add(amount);
            }
        }

        return new PmbSpendRawData(
                identity.name(),
                identity.registrationNumber(),
                beneficiaries.totalBeneficiaries(),
                paidByCategory,
                countByCategory,
                nonPmbPaid);
    }
}
