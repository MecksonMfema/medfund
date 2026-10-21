package com.medfund.shared.report.regulatory;

import java.util.List;

/**
 * A per-regulator mapping between a Java enum of report fields and their
 * corresponding cell locators inside the bundled template — either a named
 * range (preferred, template-author-defined) or a {@link LabelAnchor} fallback
 * (used when the public template does not include a named range for the field).
 *
 * <p>Every regulator (IPEC, CMS, NAIC-P, NAIC-F, PMB, AML, tax-withheld, VAT)
 * ships one implementation in the finance-service {@code com.medfund.finance.regulatory}
 * package. The outer interface is deliberately open — implementations live in a
 * downstream module that shared cannot reference at seal time — but the inner
 * {@link Mapping} hierarchy is sealed so
 * {@link RegulatoryTemplateService#write(org.apache.poi.ss.usermodel.Workbook, Mapping, Object)}
 * pattern-matches exhaustively without a default branch.
 *
 * @param <K> the enum type that declares one entry per report field
 */
public interface RegulatoryCellMap<K extends Enum<K>> {

    /** Enum key set — one entry per field the regulator wants populated. */
    Class<K> keyClass();

    /**
     * For every field, either a named-range name OR a LabelAnchor.
     * Not both — implementations pick per-field.
     */
    List<Mapping<K>> mappings();

    /** Sealed union: a field is either a named range or an anchor lookup. */
    sealed interface Mapping<K extends Enum<K>> permits NamedRange, Anchored {

        /** Enum key of the field this mapping targets. */
        K key();
    }

    /** The template carries a named range covering the cell we want to write. */
    record NamedRange<K extends Enum<K>>(K key, String rangeName) implements Mapping<K> {

        public NamedRange {
            if (key == null) throw new IllegalArgumentException("key required");
            if (rangeName == null || rangeName.isBlank()) {
                throw new IllegalArgumentException("rangeName required");
            }
        }
    }

    /** The template lacks a named range; locate the cell by row/column labels. */
    record Anchored<K extends Enum<K>>(K key, LabelAnchor anchor) implements Mapping<K> {

        public Anchored {
            if (key == null) throw new IllegalArgumentException("key required");
            if (anchor == null) throw new IllegalArgumentException("anchor required");
        }
    }
}
