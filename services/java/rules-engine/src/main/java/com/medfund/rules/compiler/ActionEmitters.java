package com.medfund.rules.compiler;

import com.medfund.rules.model.RuleAction;
import org.springframework.stereotype.Component;

/**
 * One {@link ActionEmitter} per {@link com.medfund.rules.model.ActionType} value.
 * Grouped in a single file for browseability — they're tiny and share no state,
 * but each is its own Spring bean so the compiler can pick them up by type().
 */
public final class ActionEmitters {

    private ActionEmitters() {}

    // ── Claims pipeline ──────────────────────────────────────────────────────

    @Component
    public static class RejectEmitter implements ActionEmitter {
        public String type() { return "REJECT"; }
        public void emit(StringBuilder drl, RuleAction action) {
            drl.append("    $claim.addRejection(")
               .append(quoted(action.getRejectionCode() != null ? action.getRejectionCode() : ""))
               .append(", ")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class FlagEmitter implements ActionEmitter {
        public String type() { return "FLAG_FOR_REVIEW"; }
        public void emit(StringBuilder drl, RuleAction action) {
            String code = action.getRejectionCode();
            String message = action.getMessage() != null ? action.getMessage() : "";
            if (code != null && !code.isBlank()) {
                drl.append("    $claim.addFlag(")
                   .append(quoted(code)).append(", ")
                   .append(quoted(message))
                   .append(");\n");
            } else {
                drl.append("    $claim.addFlag(")
                   .append(quoted(message))
                   .append(");\n");
            }
        }
    }

    @Component
    public static class WarnEmitter implements ActionEmitter {
        public String type() { return "WARN"; }
        public void emit(StringBuilder drl, RuleAction action) {
            String code = action.getRejectionCode();
            String message = action.getMessage() != null ? action.getMessage() : "";
            if (code != null && !code.isBlank()) {
                drl.append("    $claim.addWarning(")
                   .append(quoted(code)).append(", ")
                   .append(quoted(message))
                   .append(");\n");
            } else {
                drl.append("    $claim.addWarning(")
                   .append(quoted(message))
                   .append(");\n");
            }
        }
    }

    @Component
    public static class CapToTariffEmitter implements ActionEmitter {
        public String type() { return "CAP_TO_TARIFF"; }
        public void emit(StringBuilder drl, RuleAction action) {
            drl.append("    $detail.setApprovedAmount($detail.getTariffAmount());\n");
        }
    }

    @Component
    public static class ApplyCopayEmitter implements ActionEmitter {
        public String type() { return "APPLY_COPAY"; }
        public void emit(StringBuilder drl, RuleAction action) {
            // value: numeric → percentage; non-numeric or starts with "FIXED:" → fixed amount
            String raw = action.getValue() == null ? "0" : action.getValue().toString().trim();
            String message = action.getMessage() != null ? action.getMessage() : "";
            if (raw.startsWith("FIXED:")) {
                String amt = raw.substring(6).trim();
                drl.append("    $claim.applyFixedCopay(new java.math.BigDecimal(\"")
                   .append(escape(amt)).append("\"), ").append(quoted(message)).append(");\n");
            } else {
                drl.append("    $claim.applyPercentageCopay(")
                   .append(parseDouble(raw)).append(", ").append(quoted(message)).append(");\n");
            }
        }
    }

    // ── Member lifecycle ─────────────────────────────────────────────────────

    @Component
    public static class SetAgeGroupEmitter implements ActionEmitter {
        public String type() { return "SET_AGE_GROUP"; }
        public void emit(StringBuilder drl, RuleAction action) {
            drl.append("    $lifecycle.setAgeGroup(")
               .append(quoted(action.getValue() != null ? action.getValue().toString() : "ADULT"))
               .append(", ").append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class AutoRenewEmitter implements ActionEmitter {
        public String type() { return "AUTO_RENEW"; }
        public void emit(StringBuilder drl, RuleAction action) {
            drl.append("    $lifecycle.autoRenew(")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class TerminateEmitter implements ActionEmitter {
        public String type() { return "TERMINATE_MEMBERSHIP"; }
        public void emit(StringBuilder drl, RuleAction action) {
            drl.append("    $lifecycle.terminate(")
               .append(quoted(action.getRejectionCode() != null ? action.getRejectionCode() : "AUTO"))
               .append(", ").append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class RequireUnderwritingEmitter implements ActionEmitter {
        public String type() { return "REQUIRE_UNDERWRITING"; }
        public void emit(StringBuilder drl, RuleAction action) {
            drl.append("    $lifecycle.requireUnderwriting(")
               .append(quoted(action.getValue() != null ? action.getValue().toString() : "MED"))
               .append(", ").append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class ApplyLoadedPremiumEmitter implements ActionEmitter {
        public String type() { return "APPLY_LOADED_PREMIUM"; }
        public void emit(StringBuilder drl, RuleAction action) {
            String factor = action.getValue() == null ? "1.0" : parseDouble(action.getValue().toString());
            drl.append("    $contribution.applyLoadedPremium(")
               .append(factor).append(", ")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    // ── Contributions ────────────────────────────────────────────────────────

    @Component
    public static class SetPremiumEmitter implements ActionEmitter {
        public String type() { return "SET_PREMIUM"; }
        public void emit(StringBuilder drl, RuleAction action) {
            String amount = action.getValue() == null ? "0" : action.getValue().toString().trim();
            drl.append("    $contribution.setPremium(new java.math.BigDecimal(\"")
               .append(escape(amount)).append("\"), ")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class ApplyLateFeeEmitter implements ActionEmitter {
        public String type() { return "APPLY_LATE_FEE"; }
        public void emit(StringBuilder drl, RuleAction action) {
            String amount = action.getValue() == null ? "0" : action.getValue().toString().trim();
            drl.append("    $contribution.applyLateFee(new java.math.BigDecimal(\"")
               .append(escape(amount)).append("\"), ")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    // ── Finance ──────────────────────────────────────────────────────────────

    @Component
    public static class SchedulePaymentRunEmitter implements ActionEmitter {
        public String type() { return "SCHEDULE_PAYMENT_RUN"; }
        public void emit(StringBuilder drl, RuleAction action) {
            // value: ISO-8601 date string OR "TODAY" sentinel resolved at runtime
            String date = action.getValue() == null ? "TODAY" : action.getValue().toString().trim();
            String dateExpr = "TODAY".equalsIgnoreCase(date)
                    ? "$time.getToday()"
                    : "java.time.LocalDate.parse(\"" + escape(date) + "\")";
            drl.append("    $paymentRun.schedule(").append(dateExpr).append(", ")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class WithholdPaymentEmitter implements ActionEmitter {
        public String type() { return "WITHHOLD_PAYMENT"; }
        public void emit(StringBuilder drl, RuleAction action) {
            String pct = action.getValue() == null ? "0" : parseDouble(action.getValue().toString());
            drl.append("    $paymentRun.withhold(").append(pct).append(", ")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    @Component
    public static class MatchRecordsEmitter implements ActionEmitter {
        public String type() { return "MATCH_RECORDS"; }
        public void emit(StringBuilder drl, RuleAction action) {
            drl.append("    $paymentRun.matchRecords(")
               .append(quoted(action.getRejectionCode() != null ? action.getRejectionCode() : "AUTO"))
               .append(", ")
               .append(quoted(action.getMessage() != null ? action.getMessage() : ""))
               .append(");\n");
        }
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    /** Defensive numeric parse — falls back to "0.0" so a typo can't blow up DRL compilation. */
    static String parseDouble(String s) {
        try { return Double.toString(Double.parseDouble(s.trim())); }
        catch (Exception e) { return "0.0"; }
    }
}
