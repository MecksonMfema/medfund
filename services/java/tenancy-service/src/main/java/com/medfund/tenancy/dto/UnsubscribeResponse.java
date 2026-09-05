package com.medfund.tenancy.dto;

/**
 * {@code success=true} + the email address that was unsubscribed on happy
 * path; {@code success=false, email=null} when the token wasn't found —
 * we deliberately don't reveal whether the token existed or not to avoid
 * disclosing recipient lists to token-scanners.
 */
public record UnsubscribeResponse(boolean success, String email) {}
