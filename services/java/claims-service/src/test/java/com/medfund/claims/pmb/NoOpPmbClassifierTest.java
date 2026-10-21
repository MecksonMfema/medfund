package com.medfund.claims.pmb;

import com.medfund.claims.entity.Claim;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class NoOpPmbClassifierTest {

    @Test
    void classify_alwaysReturnsNotPmb() {
        Claim c = new Claim();
        c.setId(UUID.randomUUID());

        StepVerifier.create(new NoOpPmbClassifier().classify(c))
                .assertNext(v -> {
                    assertThat(v.isPmb()).isFalse();
                    assertThat(v.conditionCode()).isNull();
                })
                .verifyComplete();
    }

    @Test
    void pmbFactory_producesMatchingRecord() {
        PmbClassification v = PmbClassification.pmb("PMB-042");
        assertThat(v.isPmb()).isTrue();
        assertThat(v.conditionCode()).isEqualTo("PMB-042");
    }

    @Test
    void notPmbSingleton_hasStableShape() {
        assertThat(PmbClassification.NOT_PMB.isPmb()).isFalse();
        assertThat(PmbClassification.NOT_PMB.conditionCode()).isNull();
    }
}
