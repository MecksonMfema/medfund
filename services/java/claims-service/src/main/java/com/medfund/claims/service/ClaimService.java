package com.medfund.claims.service;

import com.medfund.claims.dto.AdjudicationResult;
import com.medfund.claims.dto.SubmitClaimRequest;
import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import com.medfund.claims.exception.ClaimNotFoundException;
import com.medfund.claims.exception.InvalidClaimStateException;
import com.medfund.claims.repository.ClaimLineRepository;
import com.medfund.claims.repository.ClaimRepository;
import com.medfund.shared.audit.AuditEvent;
import com.medfund.shared.audit.AuditPublisher;
import com.medfund.shared.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

@Service
public class ClaimService {

    private static final Logger log = LoggerFactory.getLogger(ClaimService.class);
    private static final Set<String> TERMINAL_STATUSES = Set.of("PAID", "REJECTED", "CANCELLED");

    private final ClaimRepository claimRepository;
    private final ClaimLineRepository claimLineRepository;
    private final AuditPublisher auditPublisher;
    private final ClaimEventPublisher eventPublisher;
    private final AdjudicationPipeline adjudicationPipeline;
    private final VerificationService verificationService;

    public ClaimService(ClaimRepository claimRepository,
                        ClaimLineRepository claimLineRepository,
                        AuditPublisher auditPublisher,
                        ClaimEventPublisher eventPublisher,
                        AdjudicationPipeline adjudicationPipeline,
                        VerificationService verificationService) {
        this.claimRepository = claimRepository;
        this.claimLineRepository = claimLineRepository;
        this.auditPublisher = auditPublisher;
        this.eventPublisher = eventPublisher;
        this.adjudicationPipeline = adjudicationPipeline;
        this.verificationService = verificationService;
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

    @Transactional
    public Mono<Claim> submit(SubmitClaimRequest request, String actorId, String actorEmail) {
        return generateClaimNumber()
            .flatMap(claimNumber -> {
                var claim = new Claim();
                claim.setId(UUID.randomUUID());
                claim.setClaimNumber(claimNumber);
                claim.setMemberId(request.memberId());
                claim.setDependantId(request.dependantId());
                claim.setProviderId(request.providerId());
                claim.setSchemeId(request.schemeId());
                claim.setBenefitId(request.benefitId());
                claim.setClaimType(request.claimType());
                claim.setStatus("SUBMITTED");
                claim.setServiceDate(request.serviceDate());
                claim.setSubmissionDate(Instant.now());
                claim.setClaimedAmount(request.claimedAmount());
                claim.setCurrencyCode(request.currencyCode());
                claim.setDiagnosisCodes(request.diagnosisCodes());
                claim.setProcedureCodes(request.procedureCodes());
                claim.setNotes(request.notes());
                claim.setVerificationCode(verificationService.generateCode());
                claim.setCreatedAt(Instant.now());
                claim.setUpdatedAt(Instant.now());
                claim.setCreatedBy(UUID.fromString(actorId));
                claim.setUpdatedBy(UUID.fromString(actorId));

                return claimRepository.save(claim);
            })
            .flatMap(savedClaim -> {
                if (request.lines() == null || request.lines().isEmpty()) {
                    return Mono.just(savedClaim);
                }

                List<ClaimLine> lines = request.lines().stream().map(lineReq -> {
                    var line = new ClaimLine();
                    line.setId(UUID.randomUUID());
                    line.setClaimId(savedClaim.getId());
                    line.setTariffCode(lineReq.tariffCode());
                    line.setDescription(lineReq.description());
                    line.setQuantity(lineReq.quantity());
                    line.setUnitPrice(lineReq.unitPrice());
                    line.setClaimedAmount(lineReq.claimedAmount());
                    line.setModifierCodes(lineReq.modifierCodes());
                    line.setCurrencyCode(lineReq.currencyCode() != null ? lineReq.currencyCode() : savedClaim.getCurrencyCode());
                    line.setCreatedAt(Instant.now());
                    return line;
                }).toList();

                return Flux.fromIterable(lines)
                    .flatMap(claimLineRepository::save)
                    .then(Mono.just(savedClaim));
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = TenantContext.get(ctx);
                return publishAudit(tenantId, "Claim", saved.getId().toString(), "CREATE",
                        actorId, actorEmail,
                        null,
                        Map.of("claimNumber", saved.getClaimNumber(), "status", saved.getStatus(),
                               "claimedAmount", saved.getClaimedAmount().toString()))
                    .then(eventPublisher.publishClaimSubmitted(
                        saved.getId().toString(),
                        saved.getClaimNumber(),
                        saved.getMemberId().toString()))
                    .thenReturn(saved);
            }));
    }

    @Transactional
    public Mono<Claim> verify(UUID claimId, String verificationCode, String actorId, String actorEmail) {
        return claimRepository.findById(claimId)
            .switchIfEmpty(Mono.error(new ClaimNotFoundException(claimId)))
            .flatMap(claim -> {
                if (!"SUBMITTED".equals(claim.getStatus())) {
                    return Mono.error(new InvalidClaimStateException(claim.getStatus(), "VERIFIED"));
                }
                if (!verificationCode.equals(claim.getVerificationCode())) {
                    return Mono.error(new InvalidClaimStateException("SUBMITTED", "VERIFIED — invalid verification code"));
                }

                String previousStatus = claim.getStatus();
                claim.setStatus("VERIFIED");
                claim.setVerifiedAt(Instant.now());
                claim.setUpdatedAt(Instant.now());
                claim.setUpdatedBy(UUID.fromString(actorId));

                return claimRepository.save(claim)
                    .flatMap(saved -> Mono.deferContextual(ctx -> {
                        String tenantId = TenantContext.get(ctx);
                        return publishAudit(tenantId, "Claim", saved.getId().toString(), "UPDATE",
                                actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .then(eventPublisher.publishClaimStatusChanged(
                                saved.getId().toString(), saved.getStatus()))
                            .thenReturn(saved);
                    }));
            });
    }

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
                        return publishAudit(tenantId, "Claim", saved.getId().toString(), "UPDATE",
                                actorId, actorEmail,
                                Map.of("status", previousStatus),
                                Map.of("status", saved.getStatus()))
                            .then(eventPublisher.publishClaimStatusChanged(
                                saved.getId().toString(), saved.getStatus()))
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
                return publishAudit(tenantId, "Claim", saved.getId().toString(), "UPDATE",
                        actorId, actorEmail,
                        Map.of("status", previousStatus),
                        Map.of("status", saved.getStatus(), "decision", result.decision()))
                    .then(eventPublisher.publishClaimAdjudicated(
                        saved.getId().toString(),
                        saved.getClaimNumber(),
                        result.decision(),
                        saved.getProviderId() != null ? saved.getProviderId().toString() : null,
                        saved.getApprovedAmount() != null ? saved.getApprovedAmount().toPlainString() : null,
                        saved.getCurrencyCode()))
                    .thenReturn(saved);
            }));
    }

    public Flux<Claim> findByClaimType(String claimType) {
        return claimRepository.findByClaimType(claimType);
    }

    @Transactional
    public Mono<Claim> submitDrugClaim(com.medfund.claims.dto.SubmitDrugClaimRequest request,
                                        String actorId, String actorEmail) {
        return generateClaimNumber()
            .flatMap(claimNumber -> {
                var claim = new Claim();
                claim.setId(UUID.randomUUID());
                claim.setClaimNumber(claimNumber);
                claim.setMemberId(request.memberId());
                claim.setDependantId(request.dependantId());
                claim.setProviderId(request.providerId());
                claim.setSchemeId(request.schemeId());
                claim.setBenefitId(request.benefitId());
                claim.setClaimType("drug");
                claim.setStatus("SUBMITTED");
                claim.setServiceDate(request.serviceDate());
                claim.setSubmissionDate(java.time.Instant.now());
                claim.setClaimedAmount(request.claimedAmount());
                claim.setCurrencyCode(request.currencyCodeOrDefault());
                claim.setDiagnosisCodes(request.diagnosisCodes());
                claim.setNotes(request.notes());
                claim.setVerificationCode(verificationService.generateCode());
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
                        .flatMap(lineReq -> {
                            var line = new ClaimLine();
                            line.setId(UUID.randomUUID());
                            line.setClaimId(saved.getId());
                            line.setTariffCode(lineReq.drugCode());
                            line.setDescription(lineReq.drugName());
                            line.setQuantity(lineReq.quantity());
                            line.setUnitPrice(lineReq.unitPrice());
                            line.setClaimedAmount(lineReq.claimedAmount());
                            line.setCurrencyCode(lineReq.currencyCode() != null ? lineReq.currencyCode() : saved.getCurrencyCode());
                            line.setCreatedAt(java.time.Instant.now());
                            return claimLineRepository.save(line);
                        })
                        .then(Mono.just(saved));
                }
                return Mono.just(saved);
            })
            .flatMap(saved -> Mono.deferContextual(ctx -> {
                String tenantId = com.medfund.shared.tenant.TenantContext.get(ctx);
                return publishAudit(tenantId, "Claim", saved.getId().toString(), "CREATE",
                    actorId, actorEmail,
                    null, Map.of("claimType", "drug", "claimNumber", saved.getClaimNumber()))
                    .then(eventPublisher.publishClaimSubmitted(
                        saved.getId().toString(), saved.getClaimNumber(),
                        saved.getMemberId().toString()))
                    .thenReturn(saved);
            }));
    }

    private Mono<String> generateClaimNumber() {
        String number = "CLM-" + ThreadLocalRandom.current().nextInt(100000, 999999);
        return claimRepository.existsByClaimNumber(number)
            .flatMap(exists -> exists ? generateClaimNumber() : Mono.just(number));
    }

    private Mono<Void> publishAudit(String tenantId, String entityType, String entityId,
                                     String action, String actorId, String actorEmail,
                                     Map<String, Object> oldValue, Map<String, Object> newValue) {
        var event = AuditEvent.create(
            tenantId != null ? tenantId : "unknown",
            entityType,
            entityId,
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
