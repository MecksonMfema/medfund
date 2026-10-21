package com.medfund.finance.reinsurance.entity;

import lombok.Getter;
import lombok.Setter;
import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.Column;
import org.springframework.data.relational.core.mapping.Table;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * XoL / StopLoss treaty layer (V083). Ordered stack; each layer defines
 * retention (per-claim or per-aggregate excess), a layer limit, a rate
 * used for premium quoting, and an informational reinstatement count.
 *
 * <p>{@code reinstatementCount} is informational only in Phase 10 — no
 * consumption tracking or reinstatement-premium computation ships. That
 * belongs to a follow-up ticket.
 */
@Getter
@Setter
@Table("treaty_layer")
public class TreatyLayer {

    @Id
    private UUID id;

    @Column("treaty_id")
    private UUID treatyId;

    @Column("layer_order")
    private Integer layerOrder;

    @Column("retention")
    private BigDecimal retention;

    @Column("layer_limit")
    private BigDecimal layerLimit;

    @Column("layer_currency")
    private String layerCurrency;

    @Column("rate")
    private BigDecimal rate;

    @Column("reinstatement_count")
    private Integer reinstatementCount;

    @Column("created_at")
    private OffsetDateTime createdAt;
}
