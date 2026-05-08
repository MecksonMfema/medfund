package com.medfund.shared.currency;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class MoneyTest {

    @Test
    void of_normalisesToRecord() {
        Money money = Money.of(new BigDecimal("100.00"), "USD");
        assertThat(money.amount()).isEqualByComparingTo("100.00");
        assertThat(money.currencyCode()).isEqualTo("USD");
    }

    @Test
    void constructor_rejectsNonIsoCode() {
        assertThatThrownBy(() -> Money.of(BigDecimal.ONE, "us"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 4217");
    }

    @Test
    void add_sameCurrency_sums() {
        Money a = Money.of(new BigDecimal("10.00"), "USD");
        Money b = Money.of(new BigDecimal("5.50"), "USD");
        assertThat(a.add(b).amount()).isEqualByComparingTo("15.50");
    }

    @Test
    void add_mixedCurrency_throws() {
        Money a = Money.of(BigDecimal.ONE, "USD");
        Money b = Money.of(BigDecimal.ONE, "ZAR");
        assertThatThrownBy(() -> a.add(b))
                .isInstanceOf(Money.MixedCurrencyException.class)
                .hasMessageContaining("USD")
                .hasMessageContaining("ZAR");
    }

    @Test
    void multiply_keepsCurrency() {
        Money a = Money.of(new BigDecimal("3.00"), "EUR");
        assertThat(a.multiply(new BigDecimal("2.5")).amount()).isEqualByComparingTo("7.50");
    }

    @Test
    void round_appliesHalfEven() {
        Money a = Money.of(new BigDecimal("2.555"), "USD");
        // HALF_EVEN rounds 2.555 to 2.56 (5 rounds toward even, 4 is even, so up to 6)
        // Actually 2.555 -> last digit kept is the hundredths (5), next is 5; tie-breaker even: 5 -> 6 since 5 is odd? HALF_EVEN: round half to nearest even.
        // 2.555 -> two decimal places: drop the trailing 5; keep 2.55 vs 2.56. Mid-point. HALF_EVEN picks even neighbour: 2.56 (6 is even).
        assertThat(a.round(2).amount()).isEqualByComparingTo("2.56");
    }

    @Test
    void zero_signum() {
        assertThat(Money.zero("USD").isZero()).isTrue();
        assertThat(Money.of(new BigDecimal("0.01"), "USD").isPositive()).isTrue();
        assertThat(Money.of(new BigDecimal("-0.01"), "USD").isNegative()).isTrue();
    }
}
