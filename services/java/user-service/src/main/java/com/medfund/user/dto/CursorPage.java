package com.medfund.user.dto;

import java.util.List;

/**
 * Generic cursor-based pagination response.
 * Consumers should request {@code limit + 1} rows, pass the extra back
 * as {@code hasMore}, and encode the last real item's sort key as {@code nextCursor}.
 */
public record CursorPage<T>(
        List<T> content,
        /** Base64(createdAt.epochMilli:id) of the last item, or null on the last page. */
        String nextCursor,
        boolean hasMore,
        int limit
) {}
