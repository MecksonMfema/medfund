package com.medfund.shared.flags;

/**
 * Hard-coded catalogue of platform-wide feature flags. Adding a value here
 * makes the flag show up in the /platform/settings UI on the next tenancy
 * service startup (the seeder inserts the row with enabled=false). Removing
 * a value leaves the DB row orphaned; PlatformFeatureFlagService filters
 * unknown keys out of the response.
 *
 * Consumers (Java + Go) look flags up via the shared FlagRegistry (Phase 3).
 * No per-tenant override capability by design.
 */
public enum PlatformFlag {
    AI_ADJUDICATION("AI-assisted claims adjudication",
        "Enable the AI service to score and pre-adjudicate claims. When off, claims go straight to human queue."),
    FRAUD_DETECTION("Fraud detection scoring",
        "Enable the AI fraud model on submitted claims. When off, no fraud flags are attached."),
    GROUP_PORTAL("Group liaison portal",
        "Enable the /group/* portal so corporate group liaisons can self-serve enrollments and reports."),
    PROVIDER_PORTAL("Provider portal",
        "Enable the /provider/* portal for healthcare providers to submit claims and view remittances."),
    MOBILE_PWA("Member PWA",
        "Enable the Flutter member PWA at /member/*. When off, the app-shell 404s.");

    private final String displayName;
    private final String description;

    PlatformFlag(String displayName, String description) {
        this.displayName = displayName;
        this.description = description;
    }

    public String displayName() { return displayName; }
    public String description() { return description; }
}
