package com.medfund.tenancy.service;

import com.medfund.tenancy.dto.MemberNumberConfigResponse;
import com.medfund.tenancy.dto.UpdateMemberNumberConfigRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

import java.util.UUID;

/**
 * Read/write of {@code public.tenants} member-number config columns.
 * A missing tenant surfaces as an {@link IllegalArgumentException} to
 * the controller so it can 404 rather than dropping to defaults.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MemberNumberConfigService {

    private final DatabaseClient db;

    public Mono<MemberNumberConfigResponse> get(UUID tenantId) {
        return db.sql("""
                SELECT id,
                       member_number_scheme,
                       member_number_prefix,
                       dependant_number_prefix,
                       member_number_random_length,
                       member_number_suffix_separator,
                       member_number_suffix_padding,
                       member_number_suffix_start
                  FROM public.tenants
                 WHERE id = :id
                """)
                .bind("id", tenantId)
                .map(row -> new MemberNumberConfigResponse(
                        row.get("id", UUID.class),
                        defaultString(row.get("member_number_scheme", String.class), "INDEPENDENT"),
                        defaultString(row.get("member_number_prefix", String.class), "MBR-"),
                        defaultString(row.get("dependant_number_prefix", String.class), "DEP-"),
                        defaultInt(row.get("member_number_random_length", Integer.class), 6),
                        defaultString(row.get("member_number_suffix_separator", String.class), "-"),
                        defaultInt(row.get("member_number_suffix_padding", Integer.class), 2),
                        defaultInt(row.get("member_number_suffix_start", Integer.class), 1)))
                .one()
                .switchIfEmpty(Mono.error(new IllegalArgumentException(
                        "Tenant not found: " + tenantId)));
    }

    public Mono<MemberNumberConfigResponse> update(UUID tenantId,
                                                    UpdateMemberNumberConfigRequest req) {
        return db.sql("""
                UPDATE public.tenants SET
                    member_number_scheme = :scheme,
                    member_number_prefix = :memberPrefix,
                    dependant_number_prefix = :dependantPrefix,
                    member_number_random_length = :randomLength,
                    member_number_suffix_separator = :sep,
                    member_number_suffix_padding = :padding,
                    member_number_suffix_start = :start,
                    updated_at = NOW()
                 WHERE id = :id
                """)
                .bind("scheme", req.memberNumberScheme())
                .bind("memberPrefix", req.memberNumberPrefix())
                .bind("dependantPrefix", req.dependantNumberPrefix())
                .bind("randomLength", req.memberNumberRandomLength())
                .bind("sep", req.memberNumberSuffixSeparator())
                .bind("padding", req.memberNumberSuffixPadding())
                .bind("start", req.memberNumberSuffixStart())
                .bind("id", tenantId)
                .fetch().rowsUpdated()
                .flatMap(rows -> {
                    if (rows == null || rows == 0L) {
                        return Mono.error(new IllegalArgumentException(
                                "Tenant not found: " + tenantId));
                    }
                    log.info("Updated member-number config for tenant {}", tenantId);
                    return get(tenantId);
                });
    }

    private static String defaultString(String v, String fallback) {
        return v == null || v.isEmpty() ? fallback : v;
    }

    private static int defaultInt(Integer v, int fallback) {
        return v == null ? fallback : v;
    }
}
