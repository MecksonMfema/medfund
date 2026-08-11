package com.medfund.claims.service;

import com.medfund.claims.service.CarcRarcMapper.CarcRarc;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Coverage for the seven cost-share bucket → CARC/RARC mapping used on
 * the member EOB (Phase 4 copayments).
 */
class CarcRarcMapperTest {

    @Test
    void allBucketsPresent_returnsFiveCodesInFixedOrder() {
        List<CarcRarc> codes = CarcRarcMapper.forCostShare(
                "100", "25", "50", "10", "5");
        assertThat(codes).extracting(CarcRarc::carc).containsExactly("1", "3", "2", "96", "45");
        assertThat(codes).extracting(CarcRarc::amount).containsExactly("100", "25", "50", "10", "5");
    }

    @Test
    void zeroBucketsAreSkipped() {
        List<CarcRarc> codes = CarcRarcMapper.forCostShare(
                "100", "0", "50", "0.00", "5");
        assertThat(codes).extracting(CarcRarc::carc).containsExactly("1", "2", "45");
    }

    @Test
    void nullBucketsAreSkipped() {
        List<CarcRarc> codes = CarcRarcMapper.forCostShare(
                null, "25", null, null, null);
        assertThat(codes).extracting(CarcRarc::carc).containsExactly("3");
    }

    @Test
    void allZero_returnsEmpty() {
        List<CarcRarc> codes = CarcRarcMapper.forCostShare("0", "0", "0", "0", "0");
        assertThat(codes).isEmpty();
    }

    @Test
    void invalidNumericStrings_areSkipped() {
        List<CarcRarc> codes = CarcRarcMapper.forCostShare(
                "not-a-number", "25", "", "10", "5");
        assertThat(codes).extracting(CarcRarc::carc).containsExactly("3", "96", "45");
    }
}
