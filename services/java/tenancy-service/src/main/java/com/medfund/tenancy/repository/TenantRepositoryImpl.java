package com.medfund.tenancy.repository;

import com.medfund.tenancy.dto.TenantQueryParams;
import com.medfund.tenancy.entity.Tenant;
import com.medfund.tenancy.util.JsonString;
import io.r2dbc.postgresql.codec.Json;
import io.r2dbc.spi.Row;
import io.r2dbc.spi.RowMetadata;
import lombok.RequiredArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.springframework.stereotype.Repository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Instant;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class TenantRepositoryImpl implements TenantRepositoryCustom {

    private final DatabaseClient db;

    @Override
    public Flux<Tenant> search(TenantQueryParams params) {
        StringBuilder sql = new StringBuilder("SELECT * FROM public.tenants WHERE 1=1");
        appendFilters(sql, params);
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT :limit OFFSET :offset");

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
        spec = bindFilters(spec, params);
        spec = spec.bind("limit", params.size()).bind("offset", params.offset());

        return spec.map(this::mapRow).all();
    }

    @Override
    public Mono<Long> countSearch(TenantQueryParams params) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM public.tenants WHERE 1=1");
        appendFilters(sql, params);

        DatabaseClient.GenericExecuteSpec spec = db.sql(sql.toString());
        spec = bindFilters(spec, params);

        return spec.map(row -> row.get(0, Long.class)).one().defaultIfEmpty(0L);
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void appendFilters(StringBuilder sql, TenantQueryParams params) {
        if (params.q() != null) {
            sql.append(" AND (name ILIKE :q OR slug ILIKE :q OR contact_email ILIKE :q)");
        }
        if (params.status() != null) {
            sql.append(" AND status = :status");
        }
        if (params.membershipModel() != null) {
            sql.append(" AND membership_model = :membershipModel");
        }
        if (params.countryCode() != null) {
            sql.append(" AND country_code = :countryCode");
        }
    }

    private DatabaseClient.GenericExecuteSpec bindFilters(
            DatabaseClient.GenericExecuteSpec spec, TenantQueryParams params) {
        if (params.q() != null) {
            spec = spec.bind("q", "%" + params.q() + "%");
        }
        if (params.status() != null) {
            spec = spec.bind("status", params.status());
        }
        if (params.membershipModel() != null) {
            spec = spec.bind("membershipModel", params.membershipModel());
        }
        if (params.countryCode() != null) {
            spec = spec.bind("countryCode", params.countryCode());
        }
        return spec;
    }

    private Tenant mapRow(Row row, RowMetadata meta) {
        Tenant t = new Tenant();
        t.setId(row.get("id", UUID.class));
        t.setName(row.get("name", String.class));
        t.setSlug(row.get("slug", String.class));
        t.setDomain(row.get("domain", String.class));
        t.setSchemaName(row.get("schema_name", String.class));
        t.setPlanId(row.get("plan_id", UUID.class));
        t.setStatus(row.get("status", String.class));
        Json settings = row.get("settings", Json.class);
        t.setSettings(settings != null ? JsonString.of(settings.asString()) : JsonString.empty());
        Json branding = row.get("branding", Json.class);
        t.setBranding(branding != null ? JsonString.of(branding.asString()) : JsonString.empty());
        t.setContactEmail(row.get("contact_email", String.class));
        t.setCountryCode(row.get("country_code", String.class));
        t.setTimezone(row.get("timezone", String.class));
        t.setMembershipModel(row.get("membership_model", String.class));
        t.setKeycloakRealm(row.get("keycloak_realm", String.class));
        t.setCreatedAt(row.get("created_at", Instant.class));
        t.setUpdatedAt(row.get("updated_at", Instant.class));
        return t;
    }
}
