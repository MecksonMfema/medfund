package com.medfund.user.reports.lifecycle.dto;

/**
 * Phase 13 §C Phase 8 per L11 — one row per group snapshot at
 * {@code asOf}. Status counts are derived from the latest
 * {@code member_status_history} row per member for that group whose
 * {@code effective_at <= asOf}.
 */
public record GroupCensusRow(
    java.util.UUID groupId,
    String  groupName,
    String  registrationNumber,
    String  contactPerson,
    String  contactEmail,
    long    activeMembers,
    long    suspendedMembers,
    long    lapsedMembers,
    long    terminatedMembers,
    long    totalMembers
) {}
