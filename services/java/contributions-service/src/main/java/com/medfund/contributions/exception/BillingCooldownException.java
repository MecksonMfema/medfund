package com.medfund.contributions.exception;

public class BillingCooldownException extends RuntimeException {
    private final int remainingMinutes;

    public BillingCooldownException(int remainingMinutes) {
        super("Billing commit is on cooldown. Try again in " + remainingMinutes + " minute(s).");
        this.remainingMinutes = remainingMinutes;
    }

    public int getRemainingMinutes() {
        return remainingMinutes;
    }
}
