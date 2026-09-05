package com.medfund.shared.security;

import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.Yaml;

import java.io.InputStream;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks in the {@link Permissions} catalogue against its YAML mirror.
 *
 * <p>{@code permissions.yaml} is the canonical catalogue; {@link Permissions}
 * is the runtime source of truth for validation. If the two drift the
 * {@code RoleController} validation gate lets through an unknown key
 * (yaml-only) or rejects a valid key (java-only) — either way a tenant
 * admin ends up with a broken role. This test fires whenever the two
 * diverge.
 */
class PermissionsTest {

    @Test
    void claimsSiuConstantsMatchYaml() {
        // Phase 19 §A + §B (Phase 7): eight SIU permissions across the Java
        // catalogue and the YAML mirror. Verifies constant values line up
        // with the yaml keys the RoleController validates against.
        assertThat(Permissions.CLAIMS_SIU_VIEW).isEqualTo("claims:siu:view");
        assertThat(Permissions.CLAIMS_SIU_CREATE).isEqualTo("claims:siu:create");
        assertThat(Permissions.CLAIMS_SIU_INVESTIGATE).isEqualTo("claims:siu:investigate");
        assertThat(Permissions.CLAIMS_SIU_ADMIN).isEqualTo("claims:siu:admin");
        assertThat(Permissions.CLAIMS_SIU_ASSIGN).isEqualTo("claims:siu:assign");
        assertThat(Permissions.CLAIMS_SIU_APPROVE).isEqualTo("claims:siu:approve");
        assertThat(Permissions.CLAIMS_SIU_REOPEN).isEqualTo("claims:siu:reopen");
        assertThat(Permissions.CLAIMS_SIU_REFER).isEqualTo("claims:siu:refer");

        Set<String> yamlKeys = loadYamlPermissionKeys();
        assertThat(yamlKeys)
                .as("permissions.yaml must mirror every SIU constant")
                .contains(
                        Permissions.CLAIMS_SIU_VIEW,
                        Permissions.CLAIMS_SIU_CREATE,
                        Permissions.CLAIMS_SIU_INVESTIGATE,
                        Permissions.CLAIMS_SIU_ADMIN,
                        Permissions.CLAIMS_SIU_ASSIGN,
                        Permissions.CLAIMS_SIU_APPROVE,
                        Permissions.CLAIMS_SIU_REOPEN,
                        Permissions.CLAIMS_SIU_REFER);

        assertThat(Permissions.ALL)
                .as("Permissions.ALL must include every SIU constant")
                .contains(
                        Permissions.CLAIMS_SIU_VIEW,
                        Permissions.CLAIMS_SIU_CREATE,
                        Permissions.CLAIMS_SIU_INVESTIGATE,
                        Permissions.CLAIMS_SIU_ADMIN,
                        Permissions.CLAIMS_SIU_ASSIGN,
                        Permissions.CLAIMS_SIU_APPROVE,
                        Permissions.CLAIMS_SIU_REOPEN,
                        Permissions.CLAIMS_SIU_REFER);
    }

    @SuppressWarnings("unchecked")
    private Set<String> loadYamlPermissionKeys() {
        try (InputStream in = Permissions.class.getResourceAsStream("/permissions.yaml")) {
            assertThat(in).as("permissions.yaml on classpath").isNotNull();
            Map<String, Object> root = new Yaml().load(in);
            List<Map<String, Object>> domains = (List<Map<String, Object>>) root.get("domains");
            Set<String> keys = new HashSet<>();
            for (Map<String, Object> domain : domains) {
                List<Map<String, Object>> perms = (List<Map<String, Object>>) domain.get("permissions");
                if (perms == null) continue;
                keys.addAll(perms.stream()
                        .map(p -> (String) p.get("key"))
                        .collect(Collectors.toSet()));
            }
            return keys;
        } catch (Exception e) {
            throw new IllegalStateException("failed to load permissions.yaml", e);
        }
    }
}
