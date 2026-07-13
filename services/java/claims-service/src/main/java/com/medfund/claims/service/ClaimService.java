package com.medfund.claims.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.claims.client.SchemeClient;
import com.medfund.claims.dto.AdjudicationResult;
import com.medfund.claims.dto.ClaimAttachment;
import com.medfund.claims.dto.ClaimFilterParams;
import com.medfund.claims.dto.ClaimLineRequest;
import com.medfund.claims.dto.ClaimResponse;
import com.medfund.claims.dto.ClaimRow;
import com.medfund.claims.dto.ClaimSubmissionResponse;
import com.medfund.claims.dto.PageResponse;
import com.medfund.claims.dto.SubmitClaimRequest;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.exception.ClaimNotFoundException;
import com.medfund.claims.exception.InvalidClaimStateException;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.claims.repository.ClaimQueryRepository;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("PAID", "REJECTED", "CANCELLED");
    /** Lines whose body is a list of itemised tariff lines. Keep in sync
     *  with LINE_ITEM_LINES in clients/angular/.../insurance-lines.ts. */
    private static final Set<String> LINE_ITEM_LINES = Set.of("HEALTH", "GROUP", "TRAVEL");

    /**
     * Per-line rule for whether a claim carries a provider. Only two
     * live modes today — every line either allows a provider (member
     * may have paid out-of-pocket, in which case the reimbursement goes
     * to the member) or forbids one entirely (LIFE / DISABILITY payouts
     * always go to the beneficiary).
     */
    public enum ProviderMode { OPTIONAL, FORBIDDEN }

    private static final Map<String, ProviderMode> PROVIDER_MODE_BY_LINE = Map.of(
            "HEALTH",     ProviderMode.OPTIONAL,  // member-reimbursed out-of-pocket is common
            "GROUP",      ProviderMode.OPTIONAL,
            "TRAVEL",     ProviderMode.OPTIONAL,
            "VEHICLE",    ProviderMode.OPTIONAL,
            "PROPERTY",   ProviderMode.OPTIONAL,
            "FUNERAL",    ProviderMode.OPTIONAL,  // funeral director OR the family
            "LIFE",       ProviderMode.FORBIDDEN,
            "DISABILITY", ProviderMode.FORBIDDEN
    );

    /**
     * Shared per-line policy — used by both the submit validator and
     * {@link AdjudicationPipeline#checkEligibility} so the two agree on
     * whether a provider is expected. Unknown lines default to OPTIONAL
     * (a spurious provider is easier to reconcile than a rejected claim).
     */
    public static ProviderMode providerMode(String line) {
        return PROVIDER_MODE_BY_LINE.getOrDefault(line, ProviderMode.OPTIONAL);
    }
    private static final ObjectMapper JSON = new ObjectMapper();

    private final ClaimRepository claimRepository;
    private final ClaimLineRepository claimLineRepository;
    private final ClaimQueryRepository claimQueryRepository;
    private final AuditPublisher auditPublisher;
    private final ClaimEventPublisher eventPublisher;
    private final AdjudicationPipeline adjudicationPipeline;
    private final SchemeClient schemeClient;
    private final com.medfund.claims.repository.BeneficiaryBenefitRepository beneficiaryBenefitRepository;
    private final DatabaseClient databaseClient;
    private final TariffBenefitResolver tariffBenefitResolver;

    public ClaimService(ClaimRepository claimRepository,
                        ClaimLineRepository claimLineRepository,
                        ClaimQueryRepository claimQueryRepository,
                        AuditPublisher auditPublisher,
                        ClaimEventPublisher eventPublisher,
                        AdjudicationPipeline adjudicationPipeline,
                        SchemeClient schemeClient,
                        com.medfund.claims.repository.BeneficiaryBenefitRepository beneficiaryBenefitRepository,
                        DatabaseClient databaseClient,
                        TariffBenefitResolver tariffBenefitResolver) {
        this.claimRepository = claimRepository;
        this.claimLineRepository = claimLineRepository;
        this.claimQueryRepository = claimQueryRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.adjudicationPipeline = adjudicationPipeline;
        this.schemeClient = schemeClient;
        this.tariffBenefitResolver = tariffBenefitResolver;
        this.beneficiaryBenefitRepository = beneficiaryBenefitRepository;
        this.databaseClient = databaseClient;
    }

    public Flux<Claim> findAll() {
        return claimRepository.findAllOrderByCreatedAtDesc();
    }

    public Mono<Claim> findById(UUID id) {
        return claimRepository.findById(id)
            .switchIfEmpty(Mono.error(new ClaimNotFoundException(id)));
    }

    public Mono<Claim> findByClaimNumber(String claimNumber) {
        return claimRepository.findByClaimNumber(claimNumber)
            .switchIfEmpty(Mono.error(new ClaimNotFoundException(claimNumber)));
    }

    public Flux<Claim> findByMemberId(UUID memberId) {
        return claimRepository.findByMemberId(memberId);
    }

    public Flux<Claim> findByProviderId(UUID providerId) {
        return claimRepository.findByProviderId(providerId);
    }

    public Flux<Claim> findByStatus(String status) {
        return claimRepository.findByStatus(status);
    }

    /**
     * Server-side paginated claims list. Joins {@code members} and
     * {@code providers} so the client renders member + provider names
     * inline — the previous unpaginated flow shipped raw UUIDs and forced
     * a per-row lookup. Feeds {@code /tenant/claims} and its status-tabbed
     * siblings (/pending, /accepted, /rejected, /staged, /captured).
     */
    public Mono<PageResponse<ClaimRow>> searchPaged(ClaimFilterParams params) {
        int page = Math.max(params.page(), 0);
        int size = Math.min(Math.max(params.size(), 1), 200);
        int offset = page * size;
        return claimQueryRepository.search(params, size, offset)
                .collectList()
                .zipWith(claimQueryRepository.count(params))
                .map(tuple -> PageResponse.of(tuple.getT1(), tuple.getT2(), page, size));
    }

    /**
     * Submit a new claim. Resolves the authoritative insurance line from
     * the scheme (client hint is advisory), validates that the required
     * line-specific fields are present, and returns a
     * {@link ClaimSubmissionResponse} envelope containing the created
     * claim.
     *
     * <p><b>Verification:</b> operator-captured claims are marked VERIFIED
     * on submit — the operator vouches for the capture, so there is no
     * out-of-band code exchange. When the provider portal ships,
     * provider-captured claims will land as SUBMITTED and require the
     * separate verification step; this method stays the operator path.
     */
    @Transactional
    public Mono<ClaimSubmissionResponse> submit(SubmitClaimRequest request, String actorId, String actorEmail) {
        return schemeClient.findById(request.schemeId())
            .switchIfEmpty(Mono.error(new IllegalArgumentException(
                "Scheme not found: " + request.schemeId())))
            .flatMap(scheme -> {
                String derivedLine = scheme.insuranceLine() != null ? scheme.insuranceLine() : "HEALTH";
                if (request.insuranceLine() != null && !request.insuranceLine().equals(derivedLine)) {
                    log.warn("Client insurance line hint '{}' differs from scheme-derived '{}' for scheme {} — using derived",
                            request.insuranceLine(), derivedLine, request.schemeId());
                }
                validateLineRequirements(request, derivedLine);
                validateProviderPolicy(request, derivedLine);

                // Batching is opt-in — pass through whatever the operator
                // typed, blank-normalising to null so an empty string
                // doesn't survive into the ledger.
                String batchNumber = blankToNull(request.batchNumber());
                // Attachments serialise to JSON; empty list persists as
                // NULL to keep rows tight for the common no-attachment case.
                String attachmentsJson = serialiseAttachments(request.attachments());
                Instant now = Instant.now();

                return generateClaimNumber()
                    .flatMap(claimNumber -> {
                        var claim = new Claim();
                        claim.setClaimNumber(claimNumber);
                        claim.setMemberId(request.memberId());
                        claim.setDependantId(request.dependantId());
                        claim.setProviderId(request.providerId());
                        claim.setSchemeId(request.schemeId());
                        claim.setBenefitId(request.benefitId());
                        claim.setClaimType(request.claimType());
                        claim.setInsuranceLine(derivedLine);
                        claim.setBatchNumber(batchNumber);   // null when operator didn't opt in
                        claim.setAttachmentsJson(attachmentsJson);
                        claim.setStatus("VERIFIED");         // operator flow — pre-verified by default
                        claim.setVerifiedAt(now);
                        claim.setServiceDate(request.serviceDate());
                        claim.setSubmissionDate(now);
                        claim.setClaimedAmount(request.claimedAmount());
                        claim.setCurrencyCode(request.currencyCode());
                        claim.setDiagnosisCodes(request.diagnosisCodes());
                        claim.setProcedureCodes(request.procedureCodes());
                        claim.setNotes(request.notes());
                        claim.setCreatedAt(now);
                        claim.setUpdatedAt(now);
                        claim.setCreatedBy(UUID.fromString(actorId));
                        claim.setUpdatedBy(UUID.fromString(actorId));
                        return claimRepository.save(claim);
                    })
                    .flatMap(savedClaim -> saveClaimLines(savedClaim, request.lines()))
                    .flatMap(savedClaim -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        var auditPayload = new LinkedHashMap<String, Object>();
                        auditPayload.put("claimNumber", savedClaim.getClaimNumber());
                        auditPayload.put("status", savedClaim.getStatus());
                        auditPayload.put("claimedAmount", savedClaim.getClaimedAmount().toString());
                        auditPayload.put("insuranceLine", derivedLine);
                        // Operator submissions land VERIFIED — emit
                        // captured alongside submitted so the "ready for
                        // adjudication" consumer picks them up without
                        // waiting for a separate verify event.
                        return publishAudit(tenantId, "Claim", savedClaim.getId().toString(),
                                    savedClaim.getClaimNumber(), "CREATE", actorId, actorEmail,
                                    null, auditPayload)
                            .then(eventPublisher.publishClaimSubmitted(
                                    savedClaim.getId().toString(), savedClaim.getClaimNumber(),
                                    savedClaim.getMemberId().toString(), derivedLine))
                            .then(eventPublisher.publishClaimCaptured(
                                    savedClaim.getId().toString(), savedClaim.getClaimNumber(),
                                    savedClaim.getMemberId().toString(), derivedLine))
                            .thenReturn(new ClaimSubmissionResponse(
                                    ClaimResponse.from(savedClaim), batchNumber));
                    }));
            });
    }

    private Mono<Claim> saveClaimLines(Claim savedClaim, List<ClaimLineRequest> lineRequests) {
        if (lineRequests == null || lineRequests.isEmpty()) return Mono.just(savedClaim);
        // V062: resolve each line's benefit_id at ingestion so the decrement
        // consumer knows which per-benefit ledger row to touch. Empty result
        // means cap-only or unmapped — either way, line.benefit_id stays null
        // and Stage 3 re-checks the tariff mapping to distinguish the two.
        // Wrap the resolver Mono in Optional so we can distinguish "no
        // benefit resolved" from "resolver hasn't emitted" — Reactor
        // forbids null values in Mono.map / defaultIfEmpty.
        return Flux.fromIterable(lineRequests)
                .flatMap(lineReq -> tariffBenefitResolver
                        .resolve(lineReq.tariffCode(), savedClaim.getSchemeId())
                        .map(java.util.Optional::of)
                        .defaultIfEmpty(java.util.Optional.<UUID>empty())
                        .map(maybeBenefitId -> {
                            var line = new ClaimLine();
                            line.setClaimId(savedClaim.getId());
                            line.setTariffCode(lineReq.tariffCode());
                            line.setDescription(lineReq.description());
                            line.setQuantity(lineReq.quantity());
                            line.setUnitPrice(lineReq.unitPrice());
                            line.setClaimedAmount(lineReq.claimedAmount());
                            line.setModifierCodes(lineReq.modifierCodes());
                            line.setCurrencyCode(lineReq.currencyCode() != null ? lineReq.currencyCode() : savedClaim.getCurrencyCode());
                            line.setBenefitId(maybeBenefitId.orElse(null));
                            line.setCreatedAt(Instant.now());
                            return line;
                        }))
                .flatMap(claimLineRepository::save)
                .then(Mono.just(savedClaim));
    }

    // ── Line-based validation ────────────────────────────────────────
    //
    // Health/Group/Travel claims are itemised; the others carry a single
    // total plus attribute fields (vehicle registration, incident, death
    // certificate, etc.). Reject the wrong shape early — silently
    // accepting a VEHICLE claim with no registration lets the ledger
    // consumer eventually explode with no way to recover.

    private void validateLineRequirements(SubmitClaimRequest req, String line) {
        boolean expectsLineItems = LINE_ITEM_LINES.contains(line);
        boolean hasLines = req.lines() != null && !req.lines().isEmpty();
        if (expectsLineItems && !hasLines) {
            throw new IllegalArgumentException(line + " claim requires at least one tariff line");
        }
        if (!expectsLineItems && hasLines) {
            throw new IllegalArgumentException(line + " claims do not accept tariff lines");
        }
        switch (line) {
            case "VEHICLE" -> {
                requireField(req.vehicleRegistration(), "vehicleRegistration", line);
                requireField(req.incidentLocation(),   "incidentLocation",    line);
            }
            case "PROPERTY" -> {
                requireField(req.propertyAddress(),  "propertyAddress",  line);
                requireField(req.incidentLocation(), "incidentLocation", line);
            }
            case "FUNERAL"    -> requireField(req.deathCertificateRef(),     "deathCertificateRef",     line);
            case "LIFE"       -> requireField(req.lifeCertificateRef(),      "lifeCertificateRef",      line);
            case "DISABILITY" -> requireField(req.disabilityAssessmentRef(), "disabilityAssessmentRef", line);
            default -> { /* HEALTH / GROUP / TRAVEL — line-item requirement already enforced above */ }
        }
    }

    private void requireField(String value, String fieldName, String line) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(line + " claim requires " + fieldName);
        }
    }

    /**
     * Enforce the per-line provider rule. FORBIDDEN lines must not
     * carry a provider — attaching one silently would let the finance
     * consumer eventually pay the wrong party. OPTIONAL lines accept
     * either shape: with a provider (network payment) or without one
     * (member-reimbursement for an out-of-pocket bill).
     */
    private void validateProviderPolicy(SubmitClaimRequest req, String line) {
        ProviderMode mode = providerMode(line);
        boolean hasProvider = req.providerId() != null;
        if (mode == ProviderMode.FORBIDDEN && hasProvider) {
            throw new IllegalArgumentException(line + " claims are paid to the member — remove the provider");
        }
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    /**
     * V061: idempotent, usage_mode-aware increment.
     *
     * <p>Adds {@code delta} to the beneficiary's ledger row and bumps
     * {@code consumed_count} by the number of accepted lines. Each accepted
     * line is first written to {@code claim_line_consumption_log} so that
     * a replay of the same adjudication (operator toggles ACCEPTED→REJECTED
     * →ACCEPTED, retry after a partial failure) does NOT double-count.
     *
     * <p>Branches on the benefit's {@code usage_mode}:
     * <ul>
     *   <li>{@code NO_TRACKING} → no-op (matches Stage 3 pass-through).</li>
     *   <li>{@code ONE_TIME_PER_BENEFICIARY} → policy_year=0 sentinel so
     *       the annual rollover never resets it.</li>
     *   <li>Everything else → current calendar year.</li>
     * </ul>
     */
    private Mono<Void> incrementBeneficiaryBenefit(Claim claim,
                                                     java.util.List<UUID> acceptedLineIds,
                                                     java.math.BigDecimal delta) {
        if (delta.signum() == 0 || acceptedLineIds.isEmpty() || claim.getBenefitId() == null) {
            return Mono.empty();
        }
        return loadBenefitUsageMode(claim.getBenefitId())
            .flatMap(mode -> {
                if ("NO_TRACKING".equals(mode)) return Mono.<Void>empty();
                int year = "ONE_TIME_PER_BENEFICIARY".equals(mode)
                        ? 0
                        : java.time.LocalDate.now().getYear();
                return logAppliedLines(claim.getId(), claim.getBenefitId(), claim.getMemberId(),
                                        claim.getDependantId(), year, acceptedLineIds, delta)
                        .flatMap(freshCount -> freshCount == 0
                                // Every line was already logged → this is a
                                // replay; skip the counter update.
                                ? Mono.<Void>empty()
                                : applyLedgerIncrement(claim, year, delta, freshCount));
            })
            .onErrorResume(e -> {
                log.warn("Failed to increment beneficiary benefit utilization for claim {}: {}",
                        claim.getId(), e.toString());
                return Mono.empty();
            })
            .then();
    }

    /**
     * V062 cap ledger increment. Runs after the per-benefit increment for
     * every adjudicated claim on a scheme that has {@code annual_member_cap}
     * set. Upserts the {@code beneficiary_annual_totals} row for the
     * beneficiary + policy year, then increments {@code consumed_amount}
     * by the aggregate approved delta. NULL cap → no-op.
     *
     * <p>Not idempotent via a separate log — the existing
     * {@code claim_line_consumption_log} inserts already gate replay by
     * failing before we get here.
     */
    private Mono<Void> incrementAnnualCap(Claim claim, java.math.BigDecimal delta) {
        if (delta == null || delta.signum() == 0 || claim.getSchemeId() == null
                || claim.getMemberId() == null) {
            return Mono.empty();
        }
        int year = claim.getServiceDate() != null
                ? claim.getServiceDate().getYear()
                : java.time.LocalDate.now().getYear();
        return databaseClient
                .sql("SELECT annual_member_cap, currency_code FROM schemes WHERE id = :id")
                .bind("id", claim.getSchemeId())
                .fetch().one()
                .flatMap(row -> {
                    if (row.get("annual_member_cap") == null) return Mono.<Void>empty();
                    String currency = row.get("currency_code") != null
                            ? row.get("currency_code").toString()
                            : (claim.getCurrencyCode() != null ? claim.getCurrencyCode() : "USD");
                    // Two-step to avoid expression-index inference: seed
                    // with ON CONFLICT DO NOTHING against the partial
                    // UNIQUE (which uses COALESCE for the dependant slot),
                    // then always UPDATE. If the seed lost the race, the
                    // UPDATE still lands on the winning row.
                    Mono<Long> seed = databaseClient.sql("""
                            INSERT INTO beneficiary_annual_totals
                                (scheme_id, member_id, dependant_id, policy_year, currency_code, consumed_amount)
                            VALUES
                                (:schemeId, :memberId, :dependantId, :year, :currency, 0)
                            ON CONFLICT DO NOTHING
                            """)
                            .bind("schemeId", claim.getSchemeId())
                            .bind("memberId", claim.getMemberId())
                            .bind("dependantId", claim.getDependantId() != null ? claim.getDependantId() : (UUID) null)
                            .bind("year", year)
                            .bind("currency", currency)
                            .fetch().rowsUpdated();
                    Mono<Long> bump = databaseClient.sql("""
                            UPDATE beneficiary_annual_totals
                               SET consumed_amount = consumed_amount + :delta,
                                   updated_at      = NOW()
                             WHERE scheme_id = :schemeId
                               AND member_id = :memberId
                               AND ((:dependantId IS NULL AND dependant_id IS NULL)
                                    OR dependant_id = :dependantId)
                               AND policy_year = :year
                            """)
                            .bind("schemeId", claim.getSchemeId())
                            .bind("memberId", claim.getMemberId())
                            .bind("dependantId", claim.getDependantId() != null ? claim.getDependantId() : (UUID) null)
                            .bind("year", year)
                            .bind("delta", delta)
                            .fetch().rowsUpdated();
                    return seed.then(bump).then();
                })
                .onErrorResume(e -> {
                    log.warn("Failed to increment annual cap ledger for claim {}: {}",
                            claim.getId(), e.toString());
                    return Mono.empty();
                });
    }

    /** Loads the benefit's usage_mode; defaults to RUNNING_BALANCE when missing. */
    private Mono<String> loadBenefitUsageMode(UUID benefitId) {
        return databaseClient
                .sql("SELECT usage_mode FROM scheme_benefits WHERE id = :id")
                .bind("id", benefitId)
                .fetch().one()
                .map(row -> row.get("usage_mode") != null
                        ? row.get("usage_mode").toString()
                        : "RUNNING_BALANCE")
                .defaultIfEmpty("RUNNING_BALANCE");
    }

    /**
     * Insert one row into {@code claim_line_consumption_log} per accepted
     * line ID. Duplicate-key errors are swallowed so replays are no-ops.
     * Returns the number of rows that were actually inserted (i.e. the
     * count of previously-unapplied lines).
     */
    private Mono<Integer> logAppliedLines(UUID claimId, UUID benefitId, UUID memberId, UUID dependantId,
                                            int policyYear, java.util.List<UUID> lineIds,
                                            java.math.BigDecimal totalDelta) {
        // Each line gets a proportional slice of the aggregate delta. Precise
        // per-line amount is not tracked at the claim level today (ClaimLine
        // doesn't carry benefitId), so we split evenly for audit; the ledger
        // still increments by the full delta once.
        java.math.BigDecimal perLine = totalDelta.divide(
                new java.math.BigDecimal(lineIds.size()), 4, java.math.RoundingMode.HALF_UP);
        return Flux.fromIterable(lineIds)
                .flatMap(lineId -> databaseClient
                        .sql("""
                                INSERT INTO claim_line_consumption_log
                                    (claim_line_id, benefit_id, member_id, dependant_id, policy_year, applied_delta)
                                VALUES
                                    (:claimLineId, :benefitId, :memberId, :dependantId, :policyYear, :delta)
                                ON CONFLICT (claim_line_id) DO NOTHING
                                """)
                        .bind("claimLineId", lineId)
                        .bind("benefitId", benefitId)
                        .bind("memberId", memberId)
                        .bind("dependantId", dependantId != null ? dependantId : (UUID) null)
                        .bind("policyYear", policyYear)
                        .bind("delta", perLine)
                        .fetch().rowsUpdated())
                .reduce(0L, Long::sum)
                .map(Long::intValue);
    }

    /**
     * Apply the counter increment to the existing ledger row. Missing row
     * is treated as a no-op — the enrolment seeder should have created it,
     * but a missing row must not fail the adjudication.
     */
    private Mono<Void> applyLedgerIncrement(Claim claim, int policyYear,
                                              java.math.BigDecimal delta, int acceptedCount) {
        return beneficiaryBenefitRepository.findOne(
                        claim.getMemberId(), claim.getDependantId(),
                        claim.getBenefitId(), policyYear)
                .flatMap(row -> {
                    var current = row.getConsumedAmount() != null
                            ? row.getConsumedAmount() : java.math.BigDecimal.ZERO;
                    row.setConsumedAmount(current.add(delta));
                    row.setConsumedCount((row.getConsumedCount() != null ? row.getConsumedCount() : 0)
                            + acceptedCount);
                    row.setUpdatedAt(Instant.now());
                    return beneficiaryBenefitRepository.save(row).then();
                });
    }

    /**
     * Apply the adjudicator's tariff / modifier overrides. Snapshots
     * the current value into {@code original_*} the first time the
     * value actually changes — never overwrites an existing snapshot,
     * so the earliest captured value stays load-bearing for audit.
     */
    private static void applyCodeOverride(ClaimLine line,
                                            com.medfund.claims.dto.LineDecisionRequest d) {
        // Tariff code — null on the request means "leave it as-is".
        if (d.tariffCode() != null) {
            String next = blankToNull(d.tariffCode());
            if (!java.util.Objects.equals(next, line.getTariffCode())) {
                if (line.getOriginalTariffCode() == null) {
                    line.setOriginalTariffCode(line.getTariffCode());
                }
                line.setTariffCode(next);
            }
        }
        if (d.modifierCodes() != null) {
            String next = blankToNull(d.modifierCodes());
            if (!java.util.Objects.equals(next, line.getModifierCodes())) {
                if (line.getOriginalModifierCodes() == null) {
                    line.setOriginalModifierCodes(line.getModifierCodes());
                }
                line.setModifierCodes(next);
            }
        }
    }

    /**
     * Serialise the attachment list to JSON. An empty or null list is
     * stored as NULL — Postgres rows without attachments should carry no
     * column bytes, and downstream ClaimResponse maps NULL back to an
     * empty list anyway.
     */
    private static String serialiseAttachments(List<ClaimAttachment> attachments) {
        if (attachments == null || attachments.isEmpty()) return null;
        try {
            return JSON.writeValueAsString(attachments);
        } catch (JsonProcessingException e) {
            // Serialisation of a fixed-shape record should never fail; if
            // it does, the operator's picked files aren't recoverable from
            // this request anyway, so surface as a 500 rather than silently
            // losing them.
            throw new IllegalStateException("failed to serialise attachments", e);
        }
    }

    // NOTE: the SUBMITTED → VERIFIED verify() step was removed on
    // 2026-07-11. Operator-captured claims are marked VERIFIED at
    // submit time, so no separate verification hop is needed. When the
    // provider portal ships, provider submissions will land SUBMITTED
    // and this method will come back to gate them into adjudication.

    @Transactional
    public Mono<Claim> adjudicate(UUID claimId, String actorId, String actorEmail) {
        return claimRepository.findById(claimId)
            .switchIfEmpty(Mono.error(new ClaimNotFoundException(claimId)))
            .flatMap(claim -> {
                if (!"VERIFIED".equals(claim.getStatus())) {
                    return Mono.error(new InvalidClaimStateException(claim.getStatus(), "IN_ADJUDICATION"));
                }

                claim.setStatus("IN_ADJUDICATION");
                claim.setUpdatedAt(Instant.now());
                claim.setUpdatedBy(UUID.fromString(actorId));

                return claimRepository.save(claim);
            })
            .flatMap(claim -> claimLineRepository.findByClaimId(claim.getId())
                .collectList()
                .flatMap(lines -> adjudicationPipeline.execute(claim, lines)
                    .flatMap(result -> applyAdjudicationResult(claim, result, actorId, actorEmail))));
    }

    @Transactional
    public Mono<Claim> updateStatus(UUID claimId, String newStatus, String actorId, String actorEmail) {
        return claimRepository.findById(claimId)
            .switchIfEmpty(Mono.error(new ClaimNotFoundException(claimId)))
            .flatMap(claim -> {
                if (TERMINAL_STATUSES.contains(claim.getStatus())) {
                    return Mono.error(new InvalidClaimStateException(claim.getStatus(), newStatus));
                }

                String previousStatus = claim.getStatus();
                claim.setStatus(newStatus);
                claim.setUpdatedAt(Instant.now());
                claim.setUpdatedBy(UUID.fromString(actorId));

                return claimRepository.save(claim)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Claim", saved.getId().toString(), saved.getClaimNumber(), "UPDATE",
                                actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .then(eventPublisher.publishClaimStatusChanged(
                                saved.getId().toString(), saved.getStatus(), saved.getInsuranceLine()))
                            .thenReturn(saved);
                    }));
            });
    }

    // ---- Private helpers ----

    private Mono<Claim> applyAdjudicationResult(Claim claim, AdjudicationResult result,
                                                  String actorId, String actorEmail) {
        String previousStatus = claim.getStatus();

        switch (result.decision()) {
            case "APPROVED" -> {
                claim.setStatus("ADJUDICATED");
                claim.setApprovedAmount(result.approvedAmount());
            }
            case "REJECTED" -> {
                claim.setStatus("REJECTED");
                claim.setRejectionReason(result.rejectionCode());
                claim.setRejectionNotes(result.rejectionNotes());
            }
            case "PARTIAL_APPROVED" -> {
                claim.setStatus("ADJUDICATED");
                claim.setApprovedAmount(result.approvedAmount());
            }
            case "MANUAL_REVIEW" -> claim.setStatus("PENDING_INFO");
            default -> claim.setStatus("PENDING_INFO");
        }

        claim.setAdjudicatedAt(Instant.now());
        claim.setAdjudicatedBy(UUID.fromString(actorId));
        claim.setUpdatedAt(Instant.now());
        claim.setUpdatedBy(UUID.fromString(actorId));

        return claimRepository.save(claim)
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Claim", saved.getId().toString(), saved.getClaimNumber(), "UPDATE",
                        actorId, actorEmail,
                        Map.of("status", previousStatus),
                        Map.of("status", saved.getStatus(), "decision", result.decision()))
                    .then(eventPublisher.publishClaimAdjudicated(
                        saved.getId().toString(),
                        saved.getClaimNumber(),
                        result.decision(),
                        saved.getProviderId() != null ? saved.getProviderId().toString() : null,
                        saved.getApprovedAmount() != null ? saved.getApprovedAmount().toPlainString() : null,
                        saved.getCurrencyCode(),
                        saved.getInsuranceLine(),
                        saved.getMemberId()    != null ? saved.getMemberId().toString()    : null,
                        saved.getDependantId() != null ? saved.getDependantId().toString() : null,
                        saved.getBenefitId()   != null ? saved.getBenefitId().toString()   : null,
                        String.valueOf(java.time.LocalDate.now().getYear())))
                    .thenReturn(saved);
            }));
    }

    public Flux<Claim> findByClaimType(String claimType) {
        return claimRepository.findByClaimType(claimType);
    }

    /**
     * Apply an adjudicator's per-line decisions. Every line on the claim
     * either lands ACCEPTED (with an approved amount, defaulting to the
     * claimed amount for a full award) or REJECTED (with a reason code).
     * The claim's aggregate {@code approvedAmount} is recomputed from
     * the accepted lines' totals; its status moves to REJECTED when all
     * lines are rejected, otherwise ADJUDICATED.
     *
     * <p>This is a manual-adjudicator path — the rules-engine pipeline
     * still lives at {@link #adjudicate}. Both can coexist; the manual
     * path lets an operator override or hand-adjudicate lines the
     * pipeline flagged for review.
     */
    @Transactional
    public Mono<Claim> applyLineDecisions(UUID claimId,
                                           List<com.medfund.claims.dto.LineDecisionRequest> decisions,
                                           String actorId, String actorEmail) {
        if (decisions == null || decisions.isEmpty()) {
            return Mono.error(new IllegalArgumentException("At least one line decision is required"));
        }
        return claimRepository.findById(claimId)
            .switchIfEmpty(Mono.error(new ClaimNotFoundException(claimId)))
            .flatMap(claim -> claimLineRepository.findByClaimId(claimId).collectList()
                .flatMap(lines -> {
                    Map<UUID, com.medfund.claims.dto.LineDecisionRequest> byId = new java.util.HashMap<>();
                    for (var d : decisions) {
                        if (d.lineId() == null || d.status() == null) {
                            return Mono.error(new IllegalArgumentException("Each decision requires lineId and status"));
                        }
                        String s = d.status().trim().toUpperCase();
                        // PENDING is valid too — lets an adjudicator save a
                        // code override (tariff / modifier) without also
                        // flipping the accept/reject flag.
                        if (!s.equals("ACCEPTED") && !s.equals("REJECTED") && !s.equals("PENDING")) {
                            return Mono.error(new IllegalArgumentException(
                                    "status must be ACCEPTED, REJECTED, or PENDING, got: " + d.status()));
                        }
                        byId.put(d.lineId(), d);
                    }
                    // Apply decisions to matching lines.
                    var updates = new java.util.ArrayList<ClaimLine>();
                    for (ClaimLine line : lines) {
                        var d = byId.get(line.getId());
                        if (d == null) continue;
                        String s = d.status().trim().toUpperCase();
                        line.setStatus(s);
                        if ("ACCEPTED".equals(s)) {
                            // Default to a full award when the adjudicator left
                            // the amount blank — matches the "everything's fine"
                            // happy path without forcing a re-key.
                            line.setApprovedAmount(d.approvedAmount() != null
                                    ? d.approvedAmount() : line.getClaimedAmount());
                            line.setRejectionReason(null);
                        } else if ("REJECTED".equals(s)) {
                            line.setApprovedAmount(java.math.BigDecimal.ZERO);
                            line.setRejectionReason(d.rejectionReason());
                        }
                        // PENDING: leave approvedAmount / rejectionReason
                        // alone — code-only edits shouldn't lose an earlier
                        // accept/reject decision.

                        // Tariff / modifier override — snapshot the operator's
                        // capture the first time the adjudicator changes either
                        // value. Never overwrite an existing original snapshot;
                        // the earliest capture is the honest record of what the
                        // claimant actually submitted.
                        applyCodeOverride(line, d);
                        updates.add(line);
                    }
                    if (updates.isEmpty()) {
                        return Mono.error(new IllegalArgumentException(
                                "None of the supplied lineIds belong to this claim"));
                    }
                    // V061 save-time guard: sum the amounts the adjudicator wants
                    // to accept and reject the whole save with 422 if the ledger
                    // can't absorb it. Runs BEFORE anything is persisted so a
                    // breach leaves the claim exactly as the operator found it.
                    java.math.BigDecimal proposedDelta = updates.stream()
                            .filter(l -> "ACCEPTED".equalsIgnoreCase(l.getStatus()))
                            .map(l -> l.getApprovedAmount() != null
                                    ? l.getApprovedAmount() : java.math.BigDecimal.ZERO)
                            .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                    return validateSaveTimeBalance(claim, proposedDelta)
                            .then(persistDecisions(claim, lines, updates, decisions, actorId, actorEmail));
                }));
    }

    /**
     * V061 save-time balance check for {@link #applyLineDecisions}. Runs
     * once at submit time so the whole approval either succeeds atomically
     * or fails cleanly with {@link org.springframework.http.HttpStatus#UNPROCESSABLE_ENTITY}.
     *
     * <p>Branches on the benefit's {@code usage_mode}:
     * <ul>
     *   <li>{@code NO_TRACKING} → always pass.</li>
     *   <li>{@code ONE_TIME_PER_BENEFICIARY} / {@code ONE_TIME_PER_PERIOD}
     *       → reject if the row's {@code consumed_count > 0} and any
     *       ACCEPTED line is present.</li>
     *   <li>{@code RUNNING_BALANCE} / {@code PER_EVENT_COUNTER} →
     *       reject if {@code consumed_amount + proposedDelta > annual_limit}.</li>
     * </ul>
     */
    private Mono<Void> validateSaveTimeBalance(Claim claim, java.math.BigDecimal proposedDelta) {
        if (proposedDelta.signum() == 0 || claim.getBenefitId() == null) {
            return Mono.empty();
        }
        return databaseClient
                .sql("""
                        SELECT b.usage_mode, b.annual_limit, s.tracks_member_balances
                          FROM scheme_benefits b
                          JOIN schemes s ON s.id = b.scheme_id
                         WHERE b.id = :benefitId
                        """)
                .bind("benefitId", claim.getBenefitId())
                .fetch().one()
                .flatMap(row -> {
                    String mode = row.get("usage_mode") != null
                            ? row.get("usage_mode").toString() : "RUNNING_BALANCE";
                    boolean tracks = row.get("tracks_member_balances") == null
                            || Boolean.parseBoolean(row.get("tracks_member_balances").toString());
                    if (!tracks || "NO_TRACKING".equals(mode)) return Mono.<Void>empty();
                    java.math.BigDecimal annual = row.get("annual_limit") != null
                            ? new java.math.BigDecimal(row.get("annual_limit").toString())
                            : null;
                    int year = "ONE_TIME_PER_BENEFICIARY".equals(mode)
                            ? 0
                            : java.time.LocalDate.now().getYear();
                    return databaseClient
                            .sql("""
                                    SELECT COALESCE(consumed_amount, 0) AS ca,
                                           COALESCE(consumed_count, 0)  AS cc
                                      FROM beneficiary_benefits
                                     WHERE member_id = :memberId
                                       AND ((:dependantId IS NULL AND dependant_id IS NULL)
                                            OR dependant_id = :dependantId)
                                       AND benefit_id = :benefitId
                                       AND policy_year = :policyYear
                                    """)
                            .bind("memberId", claim.getMemberId())
                            .bind("dependantId", claim.getDependantId() != null ? claim.getDependantId() : (UUID) null)
                            .bind("benefitId", claim.getBenefitId())
                            .bind("policyYear", year)
                            .fetch().one()
                            .flatMap(led -> {
                                java.math.BigDecimal consumed = new java.math.BigDecimal(led.get("ca").toString());
                                int count = ((Number) led.get("cc")).intValue();
                                if ("ONE_TIME_PER_BENEFICIARY".equals(mode) || "ONE_TIME_PER_PERIOD".equals(mode)) {
                                    if (count > 0) {
                                        return Mono.<Void>error(new org.springframework.web.server.ResponseStatusException(
                                                org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                                "R03-ONE_TIME_EXHAUSTED: benefit already used"));
                                    }
                                    return Mono.<Void>empty();
                                }
                                if (annual != null && annual.signum() > 0
                                        && consumed.add(proposedDelta).compareTo(annual) > 0) {
                                    java.math.BigDecimal remaining = annual.subtract(consumed);
                                    if (remaining.signum() < 0) remaining = java.math.BigDecimal.ZERO;
                                    return Mono.<Void>error(new org.springframework.web.server.ResponseStatusException(
                                            org.springframework.http.HttpStatus.UNPROCESSABLE_ENTITY,
                                            "R03-BALANCE_INSUFFICIENT: proposed " + proposedDelta
                                                    + " exceeds remaining " + remaining
                                                    + " (annual_limit=" + annual + ", consumed=" + consumed + ")"));
                                }
                                return Mono.<Void>empty();
                            })
                            .defaultIfEmpty(null)
                            .then();
                })
                .defaultIfEmpty(null)
                .then();
    }

    /** Persistence phase of {@link #applyLineDecisions} — factored out so the
     *  save-time balance check can gate it. Body of the original inline
     *  save-and-recompute flow. */
    private Mono<Claim> persistDecisions(Claim claim, List<ClaimLine> lines, List<ClaimLine> updates,
                                          List<com.medfund.claims.dto.LineDecisionRequest> decisions,
                                          String actorId, String actorEmail) {
        return Flux.fromIterable(updates)
                            .flatMap(claimLineRepository::save)
                            .collectList()
                            .flatMap(savedLines -> {
                                String previousStatus = claim.getStatus();
                                java.math.BigDecimal aggregate = lines.stream()
                                        .map(l -> l.getApprovedAmount() != null
                                                ? l.getApprovedAmount() : java.math.BigDecimal.ZERO)
                                        .reduce(java.math.BigDecimal.ZERO, java.math.BigDecimal::add);
                                boolean anyAccepted = lines.stream()
                                        .anyMatch(l -> "ACCEPTED".equalsIgnoreCase(l.getStatus()));
                                claim.setApprovedAmount(aggregate);
                                claim.setStatus(anyAccepted ? "ADJUDICATED" : "REJECTED");
                                claim.setAdjudicatedAt(Instant.now());
                                claim.setAdjudicatedBy(UUID.fromString(actorId));
                                claim.setUpdatedAt(Instant.now());
                                claim.setUpdatedBy(UUID.fromString(actorId));
                                // Accumulate the approved amount of the just-accepted
                                // lines onto the beneficiary's benefit-year counter.
                                // Only lines that flipped to ACCEPTED in THIS batch
                                // count — an already-accepted line being edited
                                // (amount tweak or code override) has already been
                                // credited and shouldn't be double-counted.
                                // V061: per-line idempotency via claim_line_consumption_log
                                // takes over the "don't double-count" job; we now pass the
                                // line IDs through and let the log table dedup.
                                java.math.BigDecimal delta = java.math.BigDecimal.ZERO;
                                java.util.List<UUID> acceptedLineIds = new java.util.ArrayList<>();
                                for (var d : decisions) {
                                    if (!"ACCEPTED".equalsIgnoreCase(d.status())) continue;
                                    // Match the persisted line to see its final approved amount.
                                    var matched = lines.stream()
                                            .filter(l -> l.getId().equals(d.lineId()))
                                            .findFirst().orElse(null);
                                    if (matched == null) continue;
                                    delta = delta.add(matched.getApprovedAmount() != null
                                            ? matched.getApprovedAmount() : java.math.BigDecimal.ZERO);
                                    acceptedLineIds.add(matched.getId());
                                }
                                final java.math.BigDecimal deltaFinal = delta;
                                final java.util.List<UUID> acceptedLineIdsFinal = acceptedLineIds;

                                return claimRepository.save(claim)
                                        .flatMap(saved -> Mono.deferContextual(ctx -> {
                                            String tenantId = TenantContext.get(ctx);
                                            String decision = anyAccepted
                                                    ? (aggregate.compareTo(saved.getClaimedAmount()) < 0
                                                        ? "PARTIAL_APPROVED" : "APPROVED")
                                                    : "REJECTED";
                                            return publishAudit(tenantId, "Claim", saved.getId().toString(),
                                                        saved.getClaimNumber(), "UPDATE",
                                                        actorId, actorEmail,
                                                        Map.of("status", previousStatus),
                                                        Map.of("status", saved.getStatus(),
                                                               "decision", decision,
                                                               "linesUpdated", updates.size()))
                                                .then(eventPublisher.publishClaimAdjudicated(
                                                        saved.getId().toString(),
                                                        saved.getClaimNumber(),
                                                        decision,
                                                        saved.getProviderId() != null ? saved.getProviderId().toString() : null,
                                                        aggregate.toPlainString(),
                                                        saved.getCurrencyCode(),
                                                        saved.getInsuranceLine(),
                                                        saved.getMemberId()    != null ? saved.getMemberId().toString()    : null,
                                                        saved.getDependantId() != null ? saved.getDependantId().toString() : null,
                                                        saved.getBenefitId()   != null ? saved.getBenefitId().toString()   : null,
                                                        String.valueOf(java.time.LocalDate.now().getYear())))
                                                .then(incrementBeneficiaryBenefit(saved, acceptedLineIdsFinal, deltaFinal))
                                                .then(incrementAnnualCap(saved, deltaFinal))
                                                .thenReturn(saved);
                                        }));
                            });
    }

    @Transactional
    public Mono<Claim> submitDrugClaim(com.medfund.claims.dto.SubmitDrugClaimRequest request,
                                        String actorId, String actorEmail) {
        return generateClaimNumber()
            .flatMap(claimNumber -> {
                var claim = new Claim();
                claim.setClaimNumber(claimNumber);
                claim.setMemberId(request.memberId());
                claim.setDependantId(request.dependantId());
                claim.setProviderId(request.providerId());
                claim.setSchemeId(request.schemeId());
                claim.setBenefitId(request.benefitId());
                claim.setClaimType("drug");
                // Drug claims are a HEALTH-line concept by construction.
                claim.setInsuranceLine("HEALTH");
                claim.setStatus("VERIFIED");
                claim.setVerifiedAt(java.time.Instant.now());
                claim.setServiceDate(request.serviceDate());
                claim.setSubmissionDate(java.time.Instant.now());
                claim.setClaimedAmount(request.claimedAmount());
                claim.setCurrencyCode(request.currencyCodeOrDefault());
                claim.setDiagnosisCodes(request.diagnosisCodes());
                claim.setNotes(request.notes());
                claim.setCreatedAt(java.time.Instant.now());
                claim.setUpdatedAt(java.time.Instant.now());
                claim.setCreatedBy(UUID.fromString(actorId));
                claim.setUpdatedBy(UUID.fromString(actorId));

                return claimRepository.save(claim);
            })
            .flatMap(saved -> {
                // Save drug claim lines as regular claim lines
                if (request.lines() != null) {
                    return Flux.fromIterable(request.lines())
                        .flatMap(lineReq -> tariffBenefitResolver
                                .resolve(lineReq.drugCode(), saved.getSchemeId())
                                .map(java.util.Optional::of)
                                .defaultIfEmpty(java.util.Optional.<UUID>empty())
                                .flatMap(maybeBenefitId -> {
                                    var line = new ClaimLine();
                                    line.setClaimId(saved.getId());
                                    line.setTariffCode(lineReq.drugCode());
                                    line.setDescription(lineReq.drugName());
                                    line.setQuantity(lineReq.quantity());
                                    line.setUnitPrice(lineReq.unitPrice());
                                    line.setClaimedAmount(lineReq.claimedAmount());
                                    line.setCurrencyCode(lineReq.currencyCode() != null ? lineReq.currencyCode() : saved.getCurrencyCode());
                                    line.setBenefitId(maybeBenefitId.orElse(null));
                                    line.setCreatedAt(java.time.Instant.now());
                                    return claimLineRepository.save(line);
                                }))
                        .then(Mono.just(saved));
                }
                return Mono.just(saved);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = com.medfund.shared.tenant.TenantContext.get(ctx);
                return publishAudit(tenantId, "Claim", saved.getId().toString(), saved.getClaimNumber(), "CREATE",
                    actorId, actorEmail,
                    null, Map.of("claimType", "drug", "claimNumber", saved.getClaimNumber()))
                    .then(eventPublisher.publishClaimSubmitted(
                        saved.getId().toString(), saved.getClaimNumber(),
                        saved.getMemberId().toString(), saved.getInsuranceLine()))
                    .thenReturn(saved);
            }));
    }

    private Mono<String> generateClaimNumber() {
        String number = "CLM-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return claimRepository.existsByClaimNumber(number)
            .flatMap(exists -> exists ? generateClaimNumber() : Mono.just(number));
    }

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId, String entityName,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
            entityName,
            action,
            actorId,
            actorEmail,
            oldValue,
            newValue,
            new String[]{"status"},
            UUID.randomUUID().toString()
        );
        return auditPublisher.publish(event);
    }
}
