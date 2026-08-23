package com.medfund.finance.producer.dto;

import java.util.List;

/**
 * Summary returned to the caller of the bulk-reassign endpoint.
 * {@code items} preserves the input order so the UI can render a per-row
 * success / failure table.
 */
public record BulkReassignReport(
        int total,
        int succeeded,
        int failed,
        List<BulkReassignItem> items
) {
    public static BulkReassignReport from(List<BulkReassignItem> items) {
        int succeeded = (int) items.stream().filter(BulkReassignItem::success).count();
        return new BulkReassignReport(items.size(), succeeded, items.size() - succeeded, items);
    }
}
