package com.medfund.finance.reinsurance.service;

import com.medfund.finance.reinsurance.entity.Treaty;
import com.medfund.finance.reinsurance.repository.TreatyApplicableLineRepository;
import com.medfund.finance.reinsurance.repository.TreatyLayerRepository;
import com.medfund.finance.reinsurance.repository.TreatyParticipantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.math.BigDecimal;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TreatyValidationServiceTest {

    @Mock TreatyParticipantRepository participantRepository;
    @Mock TreatyApplicableLineRepository applicableLineRepository;
    @Mock TreatyLayerRepository layerRepository;
    @InjectMocks TreatyValidationService service;

    @Test
    void validate_shareSumsUnder100_errors() {
        Treaty t = treaty("QUOTA_SHARE");
        when(participantRepository.sumShareByTreatyId(any())).thenReturn(Mono.just(new BigDecimal("99.9999")));

        StepVerifier.create(service.validateForActivation(t))
                .expectErrorMatches(err -> err instanceof IllegalArgumentException
                        && err.getMessage().contains("must sum to 100"))
                .verify();
    }

    @Test
    void validate_noApplicableLine_errors() {
        Treaty t = treaty("QUOTA_SHARE");
        when(participantRepository.sumShareByTreatyId(any())).thenReturn(Mono.just(new BigDecimal("100.0000")));
        when(applicableLineRepository.countByTreatyId(any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.validateForActivation(t))
                .expectErrorMatches(err -> err instanceof IllegalArgumentException
                        && err.getMessage().contains("at least one insurance line"))
                .verify();
    }

    @Test
    void validate_nonProportionalWithoutLayer_errors() {
        Treaty t = treaty("EXCESS_OF_LOSS");
        when(participantRepository.sumShareByTreatyId(any())).thenReturn(Mono.just(new BigDecimal("100.0000")));
        when(applicableLineRepository.countByTreatyId(any())).thenReturn(Mono.just(2L));
        when(layerRepository.countByTreatyId(any())).thenReturn(Mono.just(0L));

        StepVerifier.create(service.validateForActivation(t))
                .expectErrorMatches(err -> err instanceof IllegalArgumentException
                        && err.getMessage().contains("XoL/StopLoss treaties require at least one layer"))
                .verify();
    }

    @Test
    void validate_proportionalWithoutLayer_ok() {
        Treaty t = treaty("QUOTA_SHARE");
        when(participantRepository.sumShareByTreatyId(any())).thenReturn(Mono.just(new BigDecimal("100.0000")));
        when(applicableLineRepository.countByTreatyId(any())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.validateForActivation(t)).verifyComplete();
    }

    @Test
    void validate_xolWithLayer_ok() {
        Treaty t = treaty("EXCESS_OF_LOSS");
        when(participantRepository.sumShareByTreatyId(any())).thenReturn(Mono.just(new BigDecimal("100.0000")));
        when(applicableLineRepository.countByTreatyId(any())).thenReturn(Mono.just(1L));
        when(layerRepository.countByTreatyId(any())).thenReturn(Mono.just(1L));

        StepVerifier.create(service.validateForActivation(t)).verifyComplete();
    }

    private Treaty treaty(String type) {
        Treaty t = new Treaty();
        t.setId(UUID.randomUUID());
        t.setTreatyType(type);
        return t;
    }
}
