package com.medfund.tenancy.exception;

import java.time.LocalDate;

public class ExchangeRateNotFoundException extends RuntimeException {
    public ExchangeRateNotFoundException(String base, String quote, LocalDate asOf) {
        super("No exchange rate found for " + base + "->" + quote + " on or before " + asOf);
    }
}
