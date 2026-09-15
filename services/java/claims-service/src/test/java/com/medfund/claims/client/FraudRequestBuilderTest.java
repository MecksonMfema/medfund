package com.medfund.claims.client;

import com.medfund.claims.entity.Claim;
import com.medfund.claims.entity.ClaimLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class FraudRequestBuilderTest {

    private final FraudRequestBuilder builder = new FraudRequestBuilder();

    @Test
    void build_forHealthClaim_populatesDiagnosisAndProcedureCodes() {
        Claim claim = healthClaim();
        List<ClaimLine> lines = List.of(claimLine("23410"), claimLine("00250"));

        Map<String, Object> payload = builder.build(claim, lines);

        assertThat(payload).containsEntry("insurance_line", "HEALTH");
        assertThat(payload).containsEntry("currency_code", "USD");
        assertThat(payload.get("claim_id")).isNotNull();
        assertThat(payload.get("subject_id")).isEqualTo(claim.getMemberId().toString());

        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) payload.get("line_features");
        assertThat(features).containsKey("diagnosis_codes");
        assertThat(features).containsKey("procedure_codes");
        @SuppressWarnings("unchecked")
        List<String> procedureCodes = (List<String>) features.get("procedure_codes");
        assertThat(procedureCodes).containsExactly("23410", "00250");
    }

    @ParameterizedTest
    @ValueSource(strings = {"LIFE", "FUNERAL", "GROUP", "TRAVEL", "DISABILITY", "VEHICLE", "PROPERTY"})
    void build_forNonHealthLine_emitsEmptyLineFeatures(String line) {
        Claim claim = healthClaim();
        claim.setInsuranceLine(line);

        Map<String, Object> payload = builder.build(claim, List.of(claimLine("X")));

        assertThat(payload).containsEntry("insurance_line", line);
        @SuppressWarnings("unchecked")
        Map<String, Object> features = (Map<String, Object>) payload.get("line_features");
        assertThat(features).isEmpty();
    }

    @Test
    void build_forNullInsuranceLine_defaultsToHealth() {
        Claim claim = healthClaim();
        claim.setInsuranceLine(null);

        Map<String, Object> payload = builder.build(claim, List.of());

        assertThat(payload).containsEntry("insurance_line", "HEALTH");
    }

    @Test
    void build_forMotorAlias_normalizesToVehicle() {
        Claim claim = healthClaim();
        claim.setInsuranceLine("MOTOR");

        Map<String, Object> payload = builder.build(claim, List.of());

        assertThat(payload).containsEntry("insurance_line", "VEHICLE");
    }

    private Claim healthClaim() {
        Claim c = new Claim();
        c.setId(UUID.randomUUID());
        c.setMemberId(UUID.randomUUID());
        c.setProviderId(UUID.randomUUID());
        c.setInsuranceLine("HEALTH");
        c.setClaimedAmount(BigDecimal.valueOf(1500));
        c.setCurrencyCode("USD");
        c.setServiceDate(LocalDate.of(2026, 9, 15));
        c.setDiagnosisCodes("[\"K35\"]");
        return c;
    }

    private ClaimLine claimLine(String tariffCode) {
        ClaimLine line = new ClaimLine();
        line.setTariffCode(tariffCode);
        return line;
    }
}
