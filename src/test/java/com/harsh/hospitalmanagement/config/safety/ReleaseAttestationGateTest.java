package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class ReleaseAttestationGateTest {
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
}
