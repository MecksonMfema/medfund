package com.medfund.shared.currency;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.NumberFormat;
import java.util.Currency;
import java.util.Locale;

/**
 * Server-side counterpart to the Angular currency-format pipe. Used in audit
 * events and notification templates so a server-rendered amount matches the
 * format the user sees in the UI.
 */
public final class MoneyFormatter {

    private MoneyFormatter() {}

    public static String format(Money money, Locale locale) {
        Currency currency = Currency.getInstance(money.currencyCode());
        NumberFormat fmt = NumberFormat.getCurrencyInstance(locale);
        fmt.setCurrency(currency);
        fmt.setMinimumFractionDigits(currency.getDefaultFractionDigits());
        fmt.setMaximumFractionDigits(currency.getDefaultFractionDigits());
        fmt.setRoundingMode(RoundingMode.HALF_EVEN);
        return fmt.format(money.amount());
    }

    public static String format(BigDecimal amount, String currencyCode, Locale locale) {
        return format(Money.of(amount, currencyCode), locale);
    }

    public static String formatDefault(Money money) {
        return format(money, Locale.US);
    }
}
