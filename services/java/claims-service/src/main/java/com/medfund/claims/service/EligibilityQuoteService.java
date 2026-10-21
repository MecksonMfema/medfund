package com.medfund.claims.service;

import com.medfund.claims.client.MemberLookupClient;
import com.medfund.claims.client.MemberLookupClient.DependantSummary;
import com.medfund.claims.client.MemberLookupClient.MemberSummary;
import com.medfund.claims.dto.AdjudicationResult.CostShareBreakdown;
import com.medfund.claims.dto.AdjudicationResult.StageResult;
import com.medfund.claims.dto.EligibilityQuoteRequest;
import com.medfund.claims.dto.EligibilityQuoteResponse;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.costshare.CostShareConfig;
import com.medfund.claims.costshare.MemberCostShareAccumulatorReader;
import com.medfund.claims.costshare.SchemeCostShareReader;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;
import reactor.util.function.Tuples;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Point-of-service eligibility quote (Phase 3, spec G9). Runs the read-only
 * {@link AdjudicationPipeline#dryRun} against a transient claim built from
 * the request, then hands the intermediates to {@link CostShareCalculator}
 * to produce the seven-bucket estimate.
 *
 * <p>Never persists a {@link Claim} row, never publishes {@code claim.adjudicated}
 * or {@code claim.eob-issued}, and never writes to
 * {@code member_cost_share_accumulator}. Every request emits a
 * {@code medfund.claims.quote-issued} audit event through the shared
 * {@link AuditPublisher}, per G9 (audit only — no new Kafka topic).
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EligibilityQuoteService {

    private final MemberLookupClient memberLookupClient;
    private final AdjudicationPipeline pipeline;
    private final CostShareCalculator costShareCalculator;
    private final SchemeCostShareReader schemeCostShareReader;
    private final MemberCostShareAccumulatorReader accumulatorReader;
    private final AuditPublisher auditPublisher;

    public Mono<EligibilityQuoteResponse> quote(EligibilityQuoteRequest request,
                                                 UUID providerId,
                                                 String actorId,
                                                 String actorEmail) {
        return memberLookupClient.findByMemberNumber(request.memberNumber())
                .switchIfEmpty(Mono.error(new MemberNotFoundException(request.memberNumber())))
                .flatMap(member -> resolveDependant(request, member)
                        .flatMap(dep -> runQuote(member, dep, request, providerId)
                                .map(r -> Tuples.of(r, (DependantSummary) dep)))
                        .switchIfEmpty(Mono.defer(() -> runQuote(member, null, request, providerId)
                                .map(r -> Tuples.<EligibilityQuoteResponse, DependantSummary>of(
                                        r, empty())))))
                .flatMap(tuple -> {
                    EligibilityQuoteResponse response = tuple.getT1();
                    DependantSummary dependant = tuple.getT2().id() == null ? null : tuple.getT2();
                    return publishQuoteAudit(request, dependant, providerId, response, actorId, actorEmail)
                            .thenReturn(response);
                });
    }

    /**
     * Sentinel used to carry a "no dependant" through the tuple pipeline
     * without unpacking every step to null-check. Reactor tuples reject null
     * components, so we substitute a summary whose {@code id} is null and
     * unwrap it back to {@code null} at the audit step.
     */
    private static DependantSummary empty() {
        return new DependantSummary(null, null, null, null, null, null, null);
    }

    /**
     * Resolve the dependant when the request carries one; verify it belongs
     * to the sponsor named by {@code memberNumber}. Returns an empty Mono
     * when the request has no dependantId — the caller then falls through
     * to the member-only quote path.
     */
    private Mono<DependantSummary> resolveDependant(EligibilityQuoteRequest request, MemberSummary member) {
        if (request.dependantId() == null) {
            return Mono.empty();
        }
        return memberLookupClient.findDependantById(request.dependantId())
                .switchIfEmpty(Mono.error(new DependantNotFoundException(request.dependantId())))
                .flatMap(dep -> {
                    if (!member.id().equals(dep.memberId())) {
                        return Mono.error(new IllegalArgumentException(
                                "Dependant " + dep.id() + " does not belong to member "
                                        + member.memberNumber()));
                    }
                    return Mono.just(dep);
                });
    }

    private Mono<EligibilityQuoteResponse> runQuote(MemberSummary member,
                                                     DependantSummary dependant,
                                                     EligibilityQuoteRequest request,
                                                     UUID providerId) {
        if (member.schemeId() == null) {
            return Mono.error(new IllegalStateException(
                    "Member " + member.memberNumber() + " is not enrolled in a scheme"));
        }

        Claim transientClaim = buildTransientClaim(member, dependant, request, providerId);
        List<ClaimLine> transientLines = buildTransientLines(request);

        return pipeline.dryRun(transientClaim, transientLines)
                .flatMap(dryRun -> costShareCalculator
                        .compute(transientClaim, transientLines, dryRun.ruleActions(), dryRun.ruleAdjustedTotal())
                        .flatMap(breakdown -> assembleResponse(
                                member, dependant, transientClaim, dryRun.stages(), breakdown)));
    }

    private Mono<EligibilityQuoteResponse> assembleResponse(MemberSummary member,
                                                             DependantSummary dependant,
                                                             Claim claim,
                                                             List<StageResult> stages,
                                                             CostShareBreakdown breakdown) {
        LocalDate asOf = claim.getServiceDate();
        int policyYear = asOf.getYear();
        String coverage = classifyCoverage(member, dependant, stages);
        List<String> notes = stageNotes(stages);

        Mono<CostShareConfig.Scheme> schemeMono =
                schemeCostShareReader.findEffective(claim.getSchemeId(), policyYear, asOf)
                        .defaultIfEmpty(new CostShareConfig.Scheme(
                                null, claim.getSchemeId(), policyYear,
                                null, null, "INDIVIDUAL", "INDIVIDUAL",
                                "RECOVER_FROM_MEMBER", claim.getCurrencyCode(),
                                LocalDate.MIN, null));

        return schemeMono.flatMap(scs -> {
            UUID depForRead = "FAMILY".equals(scs.deductibleScope()) ? null : claim.getDependantId();
            return accumulatorReader.findFor(member.id(), depForRead, claim.getSchemeId(), policyYear)
                    .defaultIfEmpty(CostShareConfig.Accumulator.empty(
                            member.id(), depForRead, claim.getSchemeId(), policyYear, claim.getCurrencyCode()))
                    .map(acc -> {
                        BigDecimal deductibleRemaining = remainingBucket(scs.deductible(), acc.deductibleMet());
                        BigDecimal oopMaxRemaining = remainingBucket(scs.outOfPocketMax(), acc.oopMet());
                        BigDecimal planPaid = nz(breakdown.allowedAmount())
                                .subtract(nz(breakdown.memberResponsibility()))
                                .max(BigDecimal.ZERO);
                        return new EligibilityQuoteResponse(
                                coverage,
                                null,
                                deductibleRemaining,
                                nz(breakdown.allowedAmount()),
                                nz(breakdown.copayAmount()),
                                nz(breakdown.coinsuranceAmount()),
                                nz(breakdown.shortfallAmount()),
                                nz(breakdown.memberResponsibility()),
                                planPaid,
                                oopMaxRemaining,
                                claim.getCurrencyCode(),
                                notes);
                    });
        });
    }

    private Claim buildTransientClaim(MemberSummary member, DependantSummary dependant,
                                       EligibilityQuoteRequest request, UUID providerId) {
        Claim claim = new Claim();
        claim.setMemberId(member.id());
        if (dependant != null) {
            // Load-bearing: the INDIVIDUAL-scope accumulator read at
            // assembleResponse keys on getDependantId(). Without this the
            // dependant's quote silently reads the sponsor's pot.
            claim.setDependantId(dependant.id());
        }
        claim.setProviderId(providerId);
        claim.setSchemeId(member.schemeId());
        claim.setServiceDate(request.dateOfService());
        claim.setClaimedAmount(request.billedAmount());
        claim.setCurrencyCode(request.currencyCode());
        claim.setClaimType("QUOTE");
        claim.setStatus("QUOTE");
        return claim;
    }

    private List<ClaimLine> buildTransientLines(EligibilityQuoteRequest request) {
        List<String> tariffs = request.tariffCodes();
        BigDecimal perLine = tariffs.isEmpty()
                ? BigDecimal.ZERO
                : request.billedAmount().divide(BigDecimal.valueOf(tariffs.size()),
                        4, java.math.RoundingMode.HALF_UP);
        List<ClaimLine> lines = new ArrayList<>(tariffs.size());
        for (String code : tariffs) {
            ClaimLine line = new ClaimLine();
            line.setId(UUID.randomUUID());
            line.setTariffCode(code);
            line.setQuantity(1);
            line.setClaimedAmount(perLine);
            line.setUnitPrice(perLine);
            line.setCurrencyCode(request.currencyCode());
            lines.add(line);
        }
        return lines;
    }

    private String classifyCoverage(MemberSummary member, DependantSummary dependant,
                                     List<StageResult> stages) {
        if (member.status() == null) return "UNKNOWN";
        String memberStatus = member.status().toLowerCase();
        if ("terminated".equals(memberStatus)) return "TERMINATED";
        if ("suspended".equals(memberStatus)) {
            String reason = member.suspendReason();
            return reason != null && reason.toUpperCase().contains("ARREARS") ? "IN_ARREARS" : "SUSPENDED";
        }
        // When a dependant is on the ticket, take the more restrictive of
        // (sponsor status, dependant status). A dependant whose status is
        // deactivated / removed / swapped / deceased has to report
        // TERMINATED even though the sponsor is ACTIVE.
        if (dependant != null && dependant.status() != null) {
            String depStatus = dependant.status().toLowerCase();
            if ("deactivated".equals(depStatus)
                    || "removed".equals(depStatus)
                    || "swapped".equals(depStatus)
                    || "deceased".equals(depStatus)
                    || "terminated".equals(depStatus)) {
                return "TERMINATED";
            }
        }
        // If the member is active but the eligibility stage flagged them
        // (e.g. member row missing, status not active/enrolled), reflect the
        // deterministic gate rather than the raw column.
        StageResult eligibility = firstStage(stages, "Eligibility");
        if (eligibility != null && !eligibility.passed()) {
            return "INELIGIBLE";
        }
        return "ACTIVE";
    }

    private List<String> stageNotes(List<StageResult> stages) {
        List<String> notes = new ArrayList<>();
        for (StageResult s : stages) {
            if (!s.passed()) {
                notes.add(s.stageName() + ": " + s.details());
            }
        }
        return notes;
    }

    private StageResult firstStage(List<StageResult> stages, String name) {
        if (stages == null) return null;
        for (StageResult s : stages) {
            if (name.equals(s.stageName())) return s;
        }
        return null;
    }

    private Mono<Void> publishQuoteAudit(EligibilityQuoteRequest request,
                                         DependantSummary dependant,
                                         UUID providerId,
                                         EligibilityQuoteResponse response,
                                         String actorId, String actorEmail) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            Map<String, Object> newValue = new HashMap<>();
            newValue.put("memberNumber", request.memberNumber());
            newValue.put("serviceCategory", request.serviceCategory());
            newValue.put("tariffCodes", String.join(",", request.tariffCodes()));
            newValue.put("billedAmount", request.billedAmount().toPlainString());
            newValue.put("currencyCode", request.currencyCode());
            newValue.put("dateOfService", request.dateOfService().toString());
            newValue.put("coverage", response.coverage());
            newValue.put("estimatedPatientResponsibility",
                    response.estimatedPatientResponsibility() != null
                            ? response.estimatedPatientResponsibility().toPlainString() : "");
            newValue.put("estimatedPlanPaid",
                    response.estimatedPlanPaid() != null
                            ? response.estimatedPlanPaid().toPlainString() : "");
            newValue.put("providerId", providerId != null ? providerId.toString() : "");
            if (dependant != null) {
                newValue.put("dependantId", dependant.id().toString());
                newValue.put("dependantMemberNumber",
                        dependant.memberNumber() != null ? dependant.memberNumber() : "");
                newValue.put("dependantName", dependantDisplayName(dependant));
            }

            String friendlyName = dependant != null
                    ? "Eligibility quote for member " + request.memberNumber()
                            + " dep " + dependantDisplayName(dependant)
                            + " (" + request.dateOfService() + ")"
                    : "Eligibility quote for member " + request.memberNumber()
                            + " (" + request.dateOfService() + ")";
            AuditEvent event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "EligibilityQuote",
                    UUID.randomUUID().toString(),
                    friendlyName,
                    "CREATE",
                    actorId, actorEmail,
                    null, newValue,
                    new String[]{"coverage", "estimatedPatientResponsibility", "estimatedPlanPaid"},
                    UUID.randomUUID().toString());
            return auditPublisher.publish(event);
        });
    }

    private static String dependantDisplayName(DependantSummary dep) {
        String first = dep.firstName() != null ? dep.firstName() : "";
        String last = dep.lastName() != null ? dep.lastName() : "";
        String combined = (first + " " + last).trim();
        return combined.isEmpty() ? dep.id().toString() : combined;
    }

    private static BigDecimal remainingBucket(BigDecimal cap, BigDecimal consumed) {
        if (cap == null) return null;
        BigDecimal used = consumed != null ? consumed : BigDecimal.ZERO;
        return cap.subtract(used).max(BigDecimal.ZERO);
    }

    private static BigDecimal nz(BigDecimal v) { return v != null ? v : BigDecimal.ZERO; }

    /** 404-mapped exception surfaced by the controller. Extends
     *  {@link java.util.NoSuchElementException} so it slots into the
     *  existing GlobalExceptionHandler not-found path without a new
     *  handler. */
    public static class MemberNotFoundException extends java.util.NoSuchElementException {
        public MemberNotFoundException(String memberNumber) {
            super("No member found with member_number=" + memberNumber);
        }
    }

    /** 404-mapped exception for a stale/unknown dependant UUID. Same
     *  {@link java.util.NoSuchElementException} inheritance as
     *  {@link MemberNotFoundException} so the not-found handler picks it up. */
    public static class DependantNotFoundException extends java.util.NoSuchElementException {
        public DependantNotFoundException(UUID dependantId) {
            super("No dependant found with id=" + dependantId);
        }
    }
}
