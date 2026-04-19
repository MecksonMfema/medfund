package com.medfund.tenancy.dto;

public record TenantQueryParams(
        String q,
        String status,
        String membershipModel,
        String countryCode,
        int page,   // 1-indexed
        int size
) {
    public static final int DEFAULT_SIZE = 20;
    public static final int MAX_SIZE = 100;

    public static TenantQueryParams of(
            String q, String status, String membershipModel,
            String countryCode, Integer page, Integer size) {
        int pageNum = (page == null || page < 1) ? 1 : page;
        int pageSize = (size == null || size <= 0) ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
        return new TenantQueryParams(
                blank(q),
                blank(status),
                blank(membershipModel),
                blank(countryCode),
                pageNum,
                pageSize
        );
    }

    public int offset() {
        return (page - 1) * size;
    }

    private static String blank(String s) {
        return (s == null || s.isBlank()) ? null : s.trim();
    }
}
