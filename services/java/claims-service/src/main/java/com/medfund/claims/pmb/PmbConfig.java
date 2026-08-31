package com.medfund.claims.pmb;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the fallback {@link NoOpPmbClassifier} only when no other
 * {@link PmbClassifier} bean is present. Phase 17 lands
 * {@code RulesEnginePmbClassifier} and this fallback drops out with no
 * further wiring changes.
 */
@Configuration
public class PmbConfig {

    @Bean
    @ConditionalOnMissingBean(PmbClassifier.class)
    public PmbClassifier noOpPmbClassifier() {
        return new NoOpPmbClassifier();
    }
}
