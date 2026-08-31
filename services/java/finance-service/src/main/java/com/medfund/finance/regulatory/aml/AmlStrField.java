package com.medfund.finance.regulatory.aml;

/**
 * Enumerated cell targets for the per-STR filing XLSX (Phase 26). One entry
 * per named range shared across the three synthetic filing templates under
 * {@code services/java/shared/src/main/resources/report-templates/aml/}:
 *
 * <ul>
 *   <li>{@code zw-str-filing-*.xlsx} — FIU goAML export shape</li>
 *   <li>{@code za-str-filing-*.xlsx} — FIC STR shape</li>
 *   <li>{@code us-str-filing-*.xlsx} — FinCEN SAR shape</li>
 * </ul>
 *
 * <p>The layouts differ visually per regulator, but every template exposes
 * the same {@code AMLSTR_*} named ranges so {@link AmlStrFilingCellMap}
 * stays country-agnostic — {@link AmlStrFilingXlsxService#pickReportKeyFile}
 * only picks the base file and the fill logic is identical.
 *
 * <p>The per-STR shape is a single-case (single-alert) export — contrast
 * {@link AmlField} which drives the periodic {@code AML_STR} summary.
 * There is deliberately no {@code ACTIVITY_*} block; each row here is one
 * suspicious transaction that reached the FILED terminal state.
 */
public enum AmlStrField {

    // ── Header / meta ──────────────────────────────────────────────────────────
    META_REPORTING_ENTITY_NAME,
    META_REGULATOR_REFERENCE,     // FIU / FIC / FinCEN registration id
    META_COUNTRY,
    META_TEMPLATE_KEY,            // "FIU_GOAML" / "FIC" / "FINCEN_SAR"
    META_GENERATED_AT,            // ISO instant when the XLSX was rendered

    // ── Alert core ─────────────────────────────────────────────────────────────
    ALERT_ID,
    ALERT_TRANSACTION_REF,
    ALERT_TRANSACTION_TYPE,
    ALERT_AMOUNT,
    ALERT_CURRENCY,
    ALERT_MEMBER_ID,              // nullable — some alerts have no linked member
    ALERT_PROVIDER_ID,            // nullable — some alerts have no linked provider
    ALERT_DESCRIPTION,

    // ── Workflow trail ─────────────────────────────────────────────────────────
    ALERT_RAISED_AT,
    ALERT_RAISED_BY_EMAIL,
    ALERT_REVIEWED_AT,
    ALERT_REVIEWER_EMAIL,
    ALERT_REVIEW_NOTE,
    ALERT_FILED_AT,
    ALERT_FILED_BY_EMAIL,
    ALERT_FILED_REF               // regulator filing reference the filer supplied
}
