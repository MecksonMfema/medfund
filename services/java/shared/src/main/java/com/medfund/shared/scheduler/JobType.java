package com.medfund.shared.scheduler;

public enum JobType {
    BILLING_CYCLE("Billing Cycle", "Generate contribution bills for members"),
    BILLING_PREVIEW("Billing Preview", "Ad-hoc background preview of a billing cycle (no persistence)"),
    BILLING_COMMIT("Billing Commit", "Ad-hoc background commit of a billing cycle (persists contributions + invoices)"),
    OVERDUE_CHECK("Overdue Check", "Mark unpaid contributions as overdue"),
    PAYMENT_RUN("Payment Run", "Auto-execute approved payment runs"),
    AGE_PROCESSING("Age Processing", "Check dependant age limits and update status"),
    PRE_AUTH_EXPIRY("Pre-Auth Expiry", "Expire pre-authorizations past their expiry date"),
    TARIFF_ACTIVATION("Tariff Activation", "Activate/deactivate tariff schedules by effective date"),
    SCHEDULED_STATUS_ROLL("Scheduled Status Roll",
        "Roll enrolled → active on the enrolment date; apply due scheduled status changes on members and groups"),
    SCHEME_CHANGE_ROLL("Scheme Change Roll",
        "Apply APPROVED scheme_changes rows whose effective_date has arrived (flip members.scheme_id, publish SCHEME_CHANGED)"),
    ARREARS_ESCALATION("Arrears Escalation",
        "Auto-suspend / deactivate members and groups based on dunning_config thresholds"),
    BENEFIT_ROLLOVER("Benefit Rollover",
        "V061 — seed next-year beneficiary_benefits rows for RUNNING_BALANCE / ONE_TIME_PER_PERIOD / PER_EVENT_COUNTER benefits."),
    REINSURANCE_TREATY_PREMIUM("Reinsurance Treaty Premium",
        "Phase 6 — write a flat PREMIUM cession per ACTIVE non-proportional (XoL / StopLoss) treaty at inception. Idempotent via ux_cession_source_event.");

    private final String displayName;
    private final String description;

    JobType(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String getDisplayName() { return displayName; }
    public String getDescription() { return description; }
}
