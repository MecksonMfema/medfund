package com.medfund.shared.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Objects;

/**
 * Immutable (amount, currencyCode) pair. Arithmetic helpers reject mixed-currency
 * operations — use {@link CurrencyConverter} to bring two amounts into the same
 * currency before combining.
 *
 * <p>All rounding uses {@link RoundingMode#HALF_EVEN} (banker's rounding) per
 * spec at {@code .claude/multi-currency.md}.
 */
public record Money(BigDecimal amount, String currencyCode) {

    private static final RoundingMode ROUNDING = RoundingMode.HALF_EVEN;

    public Money {
        Objects.requireNonNull(amount, "amount must not be null");
        Objects.requireNonNull(currencyCode, "currencyCode must not be null");
        if (currencyCode.length() != 3) {
            throw new IllegalArgumentException("currencyCode must be ISO 4217 (3 chars), got: " + currencyCode);
        }
    }

    public static Money of(BigDecimal amount, String currencyCode) {
        return new Money(amount, currencyCode);
    }

    public static Money of(long amount, String currencyCode) {
        return new Money(BigDecimal.valueOf(amount), currencyCode);
    }

    public static Money zero(String currencyCode) {
        return new Money(BigDecimal.ZERO, currencyCode);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currencyCode);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currencyCode);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currencyCode);
    }

    public Money negate() {
        return new Money(amount.negate(), currencyCode);
    }

    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public Money round(int decimalPlaces) {
        return new Money(amount.setScale(decimalPlaces, ROUNDING), currencyCode);
    }

    private void requireSameCurrency(Money other) {
        if (!currencyCode.equals(other.currencyCode)) {
            throw new MixedCurrencyException(currencyCode, other.currencyCode);
        }
    }

    public static final class MixedCurrencyException extends RuntimeException {
        public MixedCurrencyException(String left, String right) {
            super("Cannot operate on mixed currencies: " + left + " and " + right);
        }
    }
}
