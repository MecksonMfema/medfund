package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyApplicableLineRepository;
import com.medfund.finance.reinsurance.repository.TreatyLayerRepository;
import com.medfund.finance.reinsurance.repository.TreatyParticipantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.math.BigDecimal;
import java.util.Set;

/**
 * Encapsulates the R9 activation invariants. Held in a separate service
 * so the treaty-service, activation controller, and (Phase 8) backfill
 * job all share the same rule surface.
 */
@Service
@RequiredArgsConstructor
public class TreatyValidationService {

    private static final BigDecimal HUNDRED = new BigDecimal("100.0000");
    private static final Set<String> NON_PROPORTIONAL = Set.of("EXCESS_OF_LOSS", "STOP_LOSS");

    private final TreatyParticipantRepository participantRepository;
    private final TreatyApplicableLineRepository applicableLineRepository;
    private final TreatyLayerRepository layerRepository;

    public Mono<Void> validateForActivation(Treaty treaty) {
        return participantRepository.sumShareByTreatyId(treaty.getId())
                .flatMap(sum -> {
                    if (sum.compareTo(HUNDRED) != 0) {
                        return Mono.error(new IllegalArgumentException(
                                "Treaty participants must sum to 100% share (found " + sum + ")"));
                    }
                    return applicableLineRepository.countByTreatyId(treaty.getId());
                })
                .flatMap(lineCount -> {
                    if (lineCount == 0) {
                        return Mono.error(new IllegalArgumentException(
                                "Treaty must cover at least one insurance line"));
                    }
                    if (isNonProportional(treaty.getTreatyType())) {
                        return layerRepository.countByTreatyId(treaty.getId())
                                .flatMap(layerCount -> layerCount == 0
                                        ? Mono.error(new IllegalArgumentException(
                                                "XoL/StopLoss treaties require at least one layer"))
                                        : Mono.<Void>empty());
                    }
                    return Mono.<Void>empty();
                })
                .then();
    }

    public boolean isNonProportional(String treatyType) {
        return treatyType != null && NON_PROPORTIONAL.contains(treatyType);
    }
}
