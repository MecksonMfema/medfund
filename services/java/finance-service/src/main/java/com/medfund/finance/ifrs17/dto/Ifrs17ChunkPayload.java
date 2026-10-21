package com.medfund.finance.ifrs17.dto;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Per portfolio × cohort × currency fan-out payload produced by
 * {@link com.medfund.finance.ifrs17.service.Ifrs17ShapingService}.
 * {@link com.medfund.finance.ifrs17.service.Ifrs17JobService} inserts one
 * {@code report_job_chunk} row + publishes one Kafka event per payload.
 *
 * <p>{@link #ifrs17Json()} carries the ai-service-side compute input matching
 * one of {@code PaaChunkInput} / {@code GmmChunkInput} / {@code VfaChunkInput}
 * (see {@code app/ifrs17/types.py}). The {@code measurement_model} discriminator
 * inside the map routes to the correct compute path in
 * {@code app/report/kafka.py::_ifrs17_dispatch}.
 *
 * <p>Phase 17 ships a minimal payload — identifiers + measurement-model
 * discriminator + opening balances defaulted to zero + empty locked-in curve.
 * §18 aggregator + §21 UI light up as compute results roll in; further phases
 * extend shaping to inline real per-cohort balances from
 * {@code ifrs17_opening_balance_seed} / earning_schedule / claims history.
 */
public record Ifrs17ChunkPayload(
        UUID portfolioId,
        UUID cohortId,
        String currency,
        String measurementModel,
        String coverageUnitPattern,
        String variableFeePattern,
        String financeExpensePresentation,
        String appliedRuleName,
        LocalDate reportingPeriodStart,
        LocalDate reportingPeriodEnd,
        Map<String, Object> ifrs17Json) {

    /**
     * Convenience builder — the shaping service starts from identifier fields
     * and layers on the measurement-model-driven fields as the rules fire.
     */
    public static Builder builder() {
        return new Builder();
    }

    public static final class Builder {
        private UUID portfolioId;
        private UUID cohortId;
        private String currency;
        private String measurementModel;
        private String coverageUnitPattern;
        private String variableFeePattern;
        private String financeExpensePresentation;
        private String appliedRuleName;
        private LocalDate reportingPeriodStart;
        private LocalDate reportingPeriodEnd;
        private final Map<String, Object> ifrs17Json = new LinkedHashMap<>();

        public Builder portfolioId(UUID v) { this.portfolioId = v; return this; }
        public Builder cohortId(UUID v) { this.cohortId = v; return this; }
        public Builder currency(String v) { this.currency = v; return this; }
        public Builder measurementModel(String v) { this.measurementModel = v; return this; }
        public Builder coverageUnitPattern(String v) { this.coverageUnitPattern = v; return this; }
        public Builder variableFeePattern(String v) { this.variableFeePattern = v; return this; }
        public Builder financeExpensePresentation(String v) { this.financeExpensePresentation = v; return this; }
        public Builder appliedRuleName(String v) { this.appliedRuleName = v; return this; }
        public Builder reportingPeriodStart(LocalDate v) { this.reportingPeriodStart = v; return this; }
        public Builder reportingPeriodEnd(LocalDate v) { this.reportingPeriodEnd = v; return this; }

        public Builder ifrs17Json(String key, Object value) {
            this.ifrs17Json.put(key, value);
            return this;
        }

        public Builder ifrs17JsonAll(Map<String, Object> values) {
            this.ifrs17Json.putAll(values);
            return this;
        }

        public Ifrs17ChunkPayload build() {
            return new Ifrs17ChunkPayload(portfolioId, cohortId, currency,
                    measurementModel, coverageUnitPattern, variableFeePattern,
                    financeExpensePresentation, appliedRuleName,
                    reportingPeriodStart, reportingPeriodEnd,
                    Map.copyOf(ifrs17Json));
        }
    }
}
