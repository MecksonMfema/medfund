package com.medfund.finance.consumer;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medfund.finance.client.FxConverter;
import com.medfund.finance.client.TenantConfigClient;
import com.medfund.finance.client.TenantConfigClient.CtcAutoConfig;
import com.medfund.finance.entity.CtcPayment;
import com.medfund.finance.entity.MemberPayable;
import com.medfund.finance.repository.CtcPaymentRepository;
import com.medfund.finance.repository.MemberContributionBalanceReader;
import com.medfund.finance.repository.MemberPayableRepository;
import com.medfund.finance.service.MemberBalanceService;
import com.medfund.finance.service.ProviderBalanceService;
import com.medfund.finance.util.DbErrors;
import com.medfund.shared.audit.AuditActor;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.kafka.receiver.KafkaReceiver;
import reactor.kafka.receiver.ReceiverOptions;
import reactor.util.context.Context;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Sole subscriber on {@code medfund.claims.adjudicated} inside finance-service.
 * Dispatches on {@code payeeType} (V069, per V066 payee-type column on
 * {@code claims}):
 *
 * <ul>
 *   <li><b>PROVIDER</b> (default for pre-V069 events with empty
 *       {@code payeeType}) — updates {@code provider_balances} via
 *       {@link ProviderBalanceService}.</li>
 *   <li><b>MEMBER</b> — writes a {@code member_payables} row so downstream
 *       CTC (Claims-to-Contributions) transfers have something to
 *       offset against.</li>
 * </ul>
 *
 * <p>The offset commit uses {@code .doOnSuccess} — never
 * {@code .doOnTerminate} — per {@code bug_reactor_kafka_ack_swallow}:
 * failed member-payable writes must NOT ack, otherwise Kafka at-least-once
 * turns into at-most-once for the offending record.
 */
@Component
public class ClaimAdjudicatedConsumer {

    private static final Logger log = LoggerFactory.getLogger(ClaimAdjudicatedConsumer.class);
    private static final String TOPIC = "medfund.claims.adjudicated";

    private final ReceiverOptions<String, String> receiverOptions;
    private final ProviderBalanceService providerBalanceService;
    private final MemberBalanceService memberBalanceService;
    private final MemberPayableRepository memberPayableRepository;
    private final AuditPublisher auditPublisher;
    private final ObjectMapper objectMapper;
    private final TenantConfigClient tenantConfigClient;
    private final MemberContributionBalanceReader memberContributionBalanceReader;
    private final CtcPaymentRepository ctcPaymentRepository;
    private final FxConverter fxConverter;

    public ClaimAdjudicatedConsumer(ReceiverOptions<String, String> receiverOptions,
                                    ProviderBalanceService providerBalanceService,
                                    MemberBalanceService memberBalanceService,
                                    MemberPayableRepository memberPayableRepository,
                                    AuditPublisher auditPublisher,
                                    ObjectMapper objectMapper,
                                    TenantConfigClient tenantConfigClient,
                                    MemberContributionBalanceReader memberContributionBalanceReader,
                                    CtcPaymentRepository ctcPaymentRepository,
                                    FxConverter fxConverter) {
        this.receiverOptions = receiverOptions;
        this.providerBalanceService = providerBalanceService;
        this.memberBalanceService = memberBalanceService;
        this.memberPayableRepository = memberPayableRepository;
        this.auditPublisher = auditPublisher;
        this.objectMapper = objectMapper;
        this.tenantConfigClient = tenantConfigClient;
        this.memberContributionBalanceReader = memberContributionBalanceReader;
        this.ctcPaymentRepository = ctcPaymentRepository;
        this.fxConverter = fxConverter;
    }

    @PostConstruct
    public void consume() {
        var options = receiverOptions.subscription(Collections.singleton(TOPIC));
        KafkaReceiver.create(options)
            .receive()
            .flatMap(record -> {
                try {
                    return processEvent(record.value())
                        .doOnSuccess(v -> record.receiverOffset().acknowledge())
                        .doOnError(e -> log.error("Failed to process claim adjudicated event (full chain): ", e))
                        .onErrorResume(e -> Mono.empty());
                } catch (Exception e) {
                    log.error("Error deserializing claim adjudicated event: ", e);
                    record.receiverOffset().acknowledge();
                    return Mono.empty();
                }
            })
            .doOnError(e -> log.error("Claim adjudicated consumer error: ", e))
            .retry()
            .subscribe();
    }

    public Mono<Void> processEvent(String json) {
        try {
            JsonNode node = objectMapper.readTree(json);
            String decision  = textOrNull(node, "decision");
            String payeeType = textOrNull(node, "payeeType");
            String tenantId  = textOrNull(node, "tenantId");

            if ("MEMBER".equalsIgnoreCase(payeeType)) {
                return handleMemberPayee(node, decision, tenantId);
            }
            // Default to the provider-balance branch — matches the pre-V069
            // behaviour for consumers that don't emit payeeType yet.
            return handleProviderPayee(node, decision);
        } catch (Exception e) {
            log.error("Failed to parse claim adjudicated event: ", e);
            return Mono.error(e);
        }
    }

    private Mono<Void> handleMemberPayee(JsonNode node, String decision, String tenantId) {
        String memberIdStr = textOrNull(node, "memberId");
        String claimIdStr  = textOrNull(node, "claimId");
        String claimNumber = textOrNull(node, "claimNumber");
        String currency    = textOrNull(node, "currencyCode");
        String claimed     = textOrNull(node, "claimedAmount");
        String approved    = textOrNull(node, "approvedAmount");
        String decisionUpper = decision == null ? "" : decision.toUpperCase();

        if (memberIdStr == null) {
            log.info("Skipping MEMBER-payee event without memberId: decision={}", decision);
            return Mono.empty();
        }

        // Compute balance deltas — member parity with provider (line 288):
        // total_claimed counts every event, total_approved bumps on APPROVED /
        // PARTIAL_APPROVED, total_paid is NEVER bumped from claim events for
        // members (finance-anchored settlement paths — CTC or Payment — own
        // that column; see plan Phase 1 §5 for rationale).
        BigDecimal claimedDelta = claimed != null ? new BigDecimal(claimed) : null;
        BigDecimal approvedDelta = null;
        switch (decisionUpper) {
            case "APPROVED":
            case "PARTIAL_APPROVED":
                if (approved != null) approvedDelta = new BigDecimal(approved);
                break;
            case "REJECTED":
                // claimed still counts (parity with provider path).
                break;
            case "COMMITTED":
            case "PAID":
                // Intentional no-op for members — see comment above.
                break;
            default:
                log.info("Skipping MEMBER claim event with decision={}", decision);
                return Mono.empty();
        }

        String resolvedCurrency = currency != null ? currency : "USD";
        UUID memberId = UUID.fromString(memberIdStr);
        BigDecimal claimedDeltaFinal = claimedDelta;
        BigDecimal approvedDeltaFinal = approvedDelta;

        Mono<Void> bumpBalance = (claimedDeltaFinal == null && approvedDeltaFinal == null)
            ? Mono.empty()
            : memberBalanceService.updateBalance(
                    memberId, resolvedCurrency,
                    claimedDeltaFinal, approvedDeltaFinal, null,
                    AuditActor.SYSTEM_ID, AuditActor.SYSTEM_EMAIL
                ).then();

        Mono<Void> writePayable;
        if (isApprovedDecision(decision) && approved != null && claimIdStr != null) {
            BigDecimal amount = new BigDecimal(approved);
            if (amount.signum() > 0) {
                MemberPayable mp = new MemberPayable();
                mp.setMemberId(memberId);
                mp.setClaimId(UUID.fromString(claimIdStr));
                mp.setClaimNumber(claimNumber);
                mp.setAmount(amount);
                mp.setCurrencyCode(resolvedCurrency);
                mp.setStatus("open");
                mp.setRecordedAt(Instant.now());
                writePayable = memberPayableRepository.save(mp)
                    .flatMap(saved -> publishMemberPayableAudit(saved).thenReturn(saved))
                    .flatMap(this::maybeAutoDraftCtc)
                    .onErrorResume(e -> {
                        if (DbErrors.isUniqueViolation(e)) {
                            log.info("Member payable already exists for claim {} — idempotent skip",
                                    claimIdStr);
                            return Mono.empty();
                        }
                        return Mono.error(e);
                    });
            } else {
                writePayable = Mono.empty();
            }
        } else {
            writePayable = Mono.empty();
        }

        Mono<Void> write = bumpBalance.then(writePayable);
        return tenantId != null && !tenantId.isBlank()
                ? write.contextWrite(Context.of(TenantContext.KEY, tenantId))
                : write;
    }

    /**
     * Auto-CTC (Phase 4). When the tenant has opted in via
     * {@code public.tenant_ctc_auto_config} AND the member's outstanding
     * contribution balance clears the configured threshold, drop a
     * {@code status='draft'} CTC row linked to the just-created payable.
     * <p>
     * Never auto-commits — operator review stays mandatory. See design
     * decision #4 in the plan for rationale (member consent + human in
     * the loop even when the rule is deterministic).
     * <p>
     * Any failure inside this branch is logged and swallowed: the payable
     * write must not be undone just because the auto-draft attempt failed.
     */
    private Mono<Void> maybeAutoDraftCtc(MemberPayable payable) {
        return Mono.deferContextual(ctx -> {
            String tenantStr = TenantContext.get(ctx);
            if (tenantStr == null || tenantStr.isBlank()) {
                return Mono.empty();
            }
            UUID tenantId;
            try {
                tenantId = UUID.fromString(tenantStr);
            } catch (IllegalArgumentException ex) {
                log.warn("[auto-ctc] tenant id in context is not a UUID: {}", tenantStr);
                return Mono.empty();
            }
            return tenantConfigClient.getCtcAutoConfig(tenantId)
                    .filter(CtcAutoConfig::enabled)
                    .flatMap(cfg -> evaluateAutoDraft(payable, cfg, tenantId))
                    .onErrorResume(err -> {
                        log.warn("[auto-ctc] evaluation failed for payable {} — payable stays, draft skipped: {}",
                                payable.getId(), err.getMessage());
                        return Mono.empty();
                    })
                    .then();
        });
    }

    private Mono<CtcPayment> evaluateAutoDraft(MemberPayable payable, CtcAutoConfig cfg, UUID tenantId) {
        BigDecimal threshold = cfg.minMemberBalanceThreshold() != null
                ? cfg.minMemberBalanceThreshold() : BigDecimal.ZERO;
        return memberContributionBalanceReader.getBalance(payable.getMemberId(), payable.getCurrencyCode())
                .flatMap(memberBalance -> convertIfNeeded(memberBalance, payable.getCurrencyCode(),
                                cfg.thresholdCurrency(), tenantId)
                        .filter(convertedBalance -> convertedBalance.compareTo(threshold) >= 0)
                        .flatMap(_ok -> resolveAutoDraftAmount(payable, cfg, tenantId))
                        .filter(amt -> amt.signum() > 0)
                        .flatMap(amt -> insertAutoDraftCtc(payable, amt)));
    }

    private Mono<BigDecimal> resolveAutoDraftAmount(MemberPayable payable, CtcAutoConfig cfg, UUID tenantId) {
        if (cfg.maxPerCtcAmount() == null) {
            return Mono.just(payable.getAmount());
        }
        return convertIfNeeded(cfg.maxPerCtcAmount(), cfg.thresholdCurrency(),
                        payable.getCurrencyCode(), tenantId)
                .map(cap -> payable.getAmount().min(cap));
    }

    private Mono<BigDecimal> convertIfNeeded(BigDecimal amount, String from, String to, UUID tenantId) {
        if (amount == null) return Mono.just(BigDecimal.ZERO);
        if (from == null || to == null || from.equalsIgnoreCase(to)) return Mono.just(amount);
        return fxConverter.convert(amount, from, to, LocalDate.now(ZoneOffset.UTC), tenantId);
    }

    private Mono<CtcPayment> insertAutoDraftCtc(MemberPayable payable, BigDecimal amount) {
        CtcPayment ctc = new CtcPayment();
        ctc.setMemberId(payable.getMemberId());
        ctc.setMemberPayableId(payable.getId());
        ctc.setAmount(amount);
        ctc.setCurrencyCode(payable.getCurrencyCode());
        ctc.setType("CTC");
        ctc.setStatus("draft");
        ctc.setCommitted(false);
        // createdBy left null — system-initiated, meaningful marker for the
        // "recent auto-drafts" query on the Angular auto-CTC surface.
        return ctcPaymentRepository.save(ctc)
                .flatMap(saved -> publishAutoDraftAudit(saved).thenReturn(saved));
    }

    private Mono<Void> publishAutoDraftAudit(CtcPayment ctc) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Auto-drafted CTC for member " + ctc.getMemberId()
                    + " " + ctc.getAmount().toPlainString() + " " + ctc.getCurrencyCode();
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("amount", ctc.getAmount().toPlainString());
            newValue.put("currencyCode", ctc.getCurrencyCode());
            newValue.put("memberId", ctc.getMemberId().toString());
            newValue.put("memberPayableId", ctc.getMemberPayableId().toString());
            newValue.put("status", ctc.getStatus());
            newValue.put("type", ctc.getType());
            newValue.put("source", "AUTO_CTC");
            var event = AuditEvent.create(
                    tenantId != null ? tenantId : "unknown",
                    "CtcPayment",
                    ctc.getId().toString(),
                    entityName,
                    "CREATE",
                    AuditActor.SYSTEM_ID,
                    AuditActor.SYSTEM_EMAIL,
                    null,
                    newValue,
                    new String[]{"amount", "currencyCode", "status", "type", "source"},
                    UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private Mono<Void> handleProviderPayee(JsonNode node, String decision) {
        String providerId    = textOrNull(node, "providerId");
        String currencyCode  = textOrNull(node, "currencyCode");
        String claimedAmount = textOrNull(node, "claimedAmount");
        String approvedAmount = textOrNull(node, "approvedAmount");

        if (providerId == null || currencyCode == null) {
            log.info("Skipping claim adjudicated event without provider context: decision={}", decision);
            return Mono.empty();
        }

        BigDecimal claimedDelta = claimedAmount != null ? new BigDecimal(claimedAmount) : null;
        BigDecimal approvedDelta = null;
        BigDecimal paidDelta = null;

        switch (decision == null ? "" : decision.toUpperCase()) {
            case "APPROVED":
            case "PARTIAL_APPROVED":
                if (approvedAmount != null) approvedDelta = new BigDecimal(approvedAmount);
                break;
            case "REJECTED":
                // Still counts toward the provider's totalClaimed history.
                break;
            case "COMMITTED":
            case "PAID":
                if (approvedAmount != null) paidDelta = new BigDecimal(approvedAmount);
                break;
            default:
                log.info("Skipping claim adjudicated event with decision={}", decision);
                return Mono.empty();
        }

        log.info("Processing PROVIDER claim adjudicated event: decision={}, provider={}, currency={}, " +
                        "claimedDelta={}, approvedDelta={}, paidDelta={}",
                 decision, providerId, currencyCode, claimedDelta, approvedDelta, paidDelta);

        return providerBalanceService.updateBalance(
            UUID.fromString(providerId),
            currencyCode,
            claimedDelta,
            approvedDelta,
            paidDelta,
            AuditActor.SYSTEM_ID,
            AuditActor.SYSTEM_EMAIL
        ).then();
    }

    private Mono<Void> publishMemberPayableAudit(MemberPayable mp) {
        return Mono.deferContextual(ctx -> {
            String tenantId = TenantContext.get(ctx);
            String entityName = "Payable to member " + mp.getMemberId()
                              + " " + mp.getAmount().toPlainString() + " " + mp.getCurrencyCode();
            Map<String, Object> newValue = new LinkedHashMap<>();
            newValue.put("amount", mp.getAmount().toPlainString());
            newValue.put("currencyCode", mp.getCurrencyCode());
            newValue.put("claimId", mp.getClaimId().toString());
            newValue.put("memberId", mp.getMemberId().toString());
            newValue.put("status", mp.getStatus());
            var event = AuditEvent.create(
                tenantId != null ? tenantId : "unknown",
                "MemberPayable",
                mp.getId().toString(),
                entityName,
                "CREATE",
                AuditActor.SYSTEM_ID,
                AuditActor.SYSTEM_EMAIL,
                null,
                newValue,
                new String[]{"amount", "currencyCode", "status"},
                UUID.randomUUID().toString()
            );
            return auditPublisher.publish(event);
        });
    }

    private static boolean isApprovedDecision(String decision) {
        String d = decision == null ? "" : decision.toUpperCase();
        return d.equals("APPROVED") || d.equals("PARTIAL_APPROVED");
    }

    private String textOrNull(JsonNode node, String field) {
        if (node == null || !node.hasNonNull(field)) return null;
        String v = node.get(field).asText();
        return (v == null || v.isBlank() || "null".equals(v)) ? null : v;
    }
}
