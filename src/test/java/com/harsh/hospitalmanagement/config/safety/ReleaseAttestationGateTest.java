package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ReleaseAttestationGateTest {
    private static final String EXPECTED_DIGEST = "a".repeat(64);

    @Test
    void matchingDigestAndRestrictedRuntimeRoleAllowStartup() throws Exception {
        JdbcTemplate jdbc = attestedMetadata(EXPECTED_DIGEST);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any())).thenReturn(EXPECTED_DIGEST);
        when(jdbc.queryForObject(anyString(), eq(Boolean.class))).thenReturn(false);

        assertThatCode(() -> new ReleaseAttestationGate(jdbc, "42").verifyBeforeWebServerStartup())
                .doesNotThrowAnyException();
    }

    @Test
    void missingExpectedDigestBlocksStartupBeforeExecutingCatalogQuery() throws Exception {
        JdbcTemplate jdbc = attestedMetadata(null);
        assertStartupBlocked(jdbc);
        verify(jdbc, never()).execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any());
    }

    @Test
    void changedCatalogDigestBlocksStartup() throws Exception {
        JdbcTemplate jdbc = attestedMetadata(EXPECTED_DIGEST);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any())).thenReturn("b".repeat(64));
        assertStartupBlocked(jdbc);
    }

    @Test
    void missingRoleResultCannotPassStartup() throws Exception {
        JdbcTemplate jdbc = attestedMetadata(EXPECTED_DIGEST);
        when(jdbc.execute(org.mockito.ArgumentMatchers.<ConnectionCallback<String>>any())).thenReturn(EXPECTED_DIGEST);
        assertStartupBlocked(jdbc);
    }

    @Test
    void missingReleaseTargetBlocksStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(new ReleaseAttestationGate.Identity("hms", "runtime", 42L, 180001));
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(0);

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "42").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");
    }

    @Test
    void partialLedgerBlocksStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(new ReleaseAttestationGate.Identity("hms", "runtime", 42L, 180001));
        when(jdbc.queryForObject(anyString(), any(Class.class))).thenReturn(1);
        when(jdbc.query(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(List.of(new ReleaseAttestationGate.Migration(1, "BASELINE", "a".repeat(64))));

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "42").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");
    }

    @Test
    void blankExpectedOidBlocksStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(new ReleaseAttestationGate.Identity("hms", "runtime", 42L, 180001));

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "   ").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, null).verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");
    }

    @Test
    void invalidExpectedOidBlocksStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(new ReleaseAttestationGate.Identity("hms", "runtime", 42L, 180001));

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "not-a-number").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "0").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "-42").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");
    }

    @Test
    void mismatchedExpectedOidBlocksStartup() {
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), any(org.springframework.jdbc.core.RowMapper.class)))
                .thenReturn(new ReleaseAttestationGate.Identity("hms", "runtime", 42L, 180001));

        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "99").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("startup blocked");
    }

    private static JdbcTemplate attestedMetadata(String digest) throws Exception {
        var entries = new ArrayList<ReleaseAttestationGate.Migration>();
        var migrations = Pattern.compile("\"version\": (\\d+), \"id\": \"([^\"]+)\","
                        + " \"path\": \"[^\"]+\", \"sha256\": \"([0-9a-f]{64})\"")
                .matcher(Files.readString(Path.of("database/migration-manifest.json")));
        while (migrations.find()) {
            entries.add(new ReleaseAttestationGate.Migration(Integer.parseInt(migrations.group(1)),
                    migrations.group(2), migrations.group(3)));
        }
        JdbcTemplate jdbc = mock(JdbcTemplate.class);
        when(jdbc.queryForObject(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ReleaseAttestationGate.Identity>>any()))
                .thenReturn(new ReleaseAttestationGate.Identity("hms", "runtime", 42L, 180001));
        when(jdbc.queryForObject(anyString(), eq(Integer.class))).thenReturn(1);
        when(jdbc.queryForObject(anyString(), eq(String.class))).thenReturn(digest);
        when(jdbc.query(anyString(), org.mockito.ArgumentMatchers.<RowMapper<ReleaseAttestationGate.Migration>>any()))
                .thenReturn(entries);
        return jdbc;
    }

    private static void assertStartupBlocked(JdbcTemplate jdbc) {
        assertThatThrownBy(() -> new ReleaseAttestationGate(jdbc, "42").verifyBeforeWebServerStartup())
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("startup blocked");
    }
}
