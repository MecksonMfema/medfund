package com.medfund.user.endorsement.util;

import com.medfund.user.endorsement.repository.EndorsementRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

import java.time.LocalDate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EndorsementReferenceGeneratorTest {

    @Mock EndorsementRepository endorsementRepository;

    @Test
    void nextEndorsementReference_zeroPadsSixDigits_startsAtOne() {
        String prefix = "END-" + LocalDate.now().getYear() + "-";
        when(endorsementRepository.countByReferenceStartingWith(prefix)).thenReturn(Mono.just(0L));

        EndorsementReferenceGenerator gen = new EndorsementReferenceGenerator(endorsementRepository);
        StepVerifier.create(gen.nextEndorsementReference())
                .assertNext(ref -> assertThat(ref).isEqualTo(prefix + "000001"))
                .verifyComplete();
    }

    @Test
    void nextEndorsementReference_incrementsFromExistingCount() {
        String prefix = "END-" + LocalDate.now().getYear() + "-";
        when(endorsementRepository.countByReferenceStartingWith(prefix)).thenReturn(Mono.just(41L));

        EndorsementReferenceGenerator gen = new EndorsementReferenceGenerator(endorsementRepository);
        StepVerifier.create(gen.nextEndorsementReference())
                .assertNext(ref -> assertThat(ref).isEqualTo(prefix + "000042"))
                .verifyComplete();
    }

    @Test
    void nextEndorsementReference_queriesForCurrentYearPrefix() {
        when(endorsementRepository.countByReferenceStartingWith(org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Mono.just(0L));

        EndorsementReferenceGenerator gen = new EndorsementReferenceGenerator(endorsementRepository);
        gen.nextEndorsementReference().block();

        ArgumentCaptor<String> cap = ArgumentCaptor.forClass(String.class);
        verify(endorsementRepository).countByReferenceStartingWith(cap.capture());
        assertThat(cap.getValue()).isEqualTo("END-" + LocalDate.now().getYear() + "-");
    }
}
