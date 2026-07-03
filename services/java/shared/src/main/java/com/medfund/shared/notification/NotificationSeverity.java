package com.medfund.shared.notification;

/**
 * Cosmetic hint the bell uses to pick an icon colour. Producers should
 * choose the value that best matches the domain event; the UI treats
 * unknown strings as {@code INFO}.
 */
public final class NotificationSeverity {
    public static final String INFO    = "info";
    public static final String SUCCESS = "success";
    public static final String WARNING = "warning";
    public static final String ERROR   = "error";

    private NotificationSeverity() {}
}
