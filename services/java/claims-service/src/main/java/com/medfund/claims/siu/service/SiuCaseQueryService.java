package com.medfund.claims.siu.service;

import com.medfund.claims.siu.dto.FraudFlagResponse;
import com.medfund.claims.siu.dto.SiuCaseNoteResponse;
import com.medfund.claims.siu.dto.SiuCaseResponse;
import com.medfund.claims.siu.dto.SiuCaseSummaryResponse;
import com.medfund.claims.siu.dto.SiuEvidenceResponse;
import com.medfund.claims.siu.dto.SiuReferralResponse;
import com.medfund.claims.siu.repository.FraudFlagRepository;
import com.medfund.claims.siu.repository.SiuCaseNoteRepository;
import com.medfund.claims.siu.repository.SiuCaseRepository;
import com.medfund.claims.siu.repository.SiuEvidenceRepository;
import com.medfund.claims.siu.repository.SiuReferralRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.UUID;

/**
 * Read side for the SIU workqueue + case-detail pages. Kept separate from
 * {@link SiuCaseService} so the write side stays free of DTO shaping and
 * cross-repo joins.
 */
@Service
@RequiredArgsConstructor
public class SiuCaseQueryService {

    private final SiuCaseRepository caseRepo;
    private final SiuCaseNoteRepository noteRepo;
    private final FraudFlagRepository flagRepo;
    private final SiuEvidenceRepository evidenceRepo;
    private final SiuReferralRepository referralRepo;

    public Flux<SiuCaseSummaryResponse> findAll(String status, UUID assignedTo) {
        Flux<com.medfund.claims.siu.entity.SiuCase> stream;
        if (assignedTo != null) {
            stream = caseRepo.findAllByAssignedToOrderByOpenedAtDesc(assignedTo);
        } else if (status != null && !status.isBlank()) {
            stream = caseRepo.findAllByStatusOrderByOpenedAtDesc(status);
        } else {
            stream = caseRepo.findAll();
        }
        return stream.flatMap(kase ->
                flagRepo.findAllBySiuCaseIdOrderByFlaggedAtDesc(kase.getId())
                        .count()
                        .map(count -> SiuCaseSummaryResponse.from(kase, count))
        );
    }

    public Mono<SiuCaseResponse> findById(UUID caseId) {
        Mono<List<FraudFlagResponse>> flags = flagRepo
                .findAllBySiuCaseIdOrderByFlaggedAtDesc(caseId)
                .map(FraudFlagResponse::from)
                .collectList();
        Mono<List<SiuCaseNoteResponse>> notes = noteRepo
                .findAllByCaseIdOrderByCreatedAtAsc(caseId)
                .map(SiuCaseNoteResponse::from)
                .collectList();
        Mono<List<SiuEvidenceResponse>> evidence = evidenceRepo
                .findAllByCaseIdOrderByUploadedAtDesc(caseId)
                .map(SiuEvidenceResponse::from)
                .collectList();
        Mono<List<SiuReferralResponse>> referrals = referralRepo
                .findAllByCaseIdOrderByReferredAtDesc(caseId)
                .map(SiuReferralResponse::from)
                .collectList();
        return caseRepo.findById(caseId)
                .flatMap(kase -> Mono.zip(flags, notes, evidence, referrals)
                        .map(t -> SiuCaseResponse.from(kase, t.getT1(), t.getT2(),
                                t.getT3(), t.getT4())));
    }
}
