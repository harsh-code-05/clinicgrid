package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationNegativeContractTest {
    private final Path root = Path.of("").toAbsolutePath();

    @Test
    void ai02DeclaresDefaultNullabilityAndAllAllowedSources() throws Exception {
        String migration = Files.readString(root.resolve("database/ai-02-cloudflare-source.sql"));
        String verifier = Files.readString(root.resolve("database/ai-02-verify.sql"));
        assertThat(migration).contains("SET DEFAULT 'FAQ_FALLBACK'", "SET NOT NULL", "ck_ai_chat_messages_source");
        assertThat(migration).contains("CLOUDFLARE_LLM", "FAQ_FALLBACK", "EMERGENCY_ESCALATION", "USER");
        assertThat(verifier).contains("source column type, nullability or default differs", "source CHECK differs");
    }

    @Test
    void bill01VerifierCoversAuditStatusPaymentAndForeignKeyContracts() throws Exception {
        String migration = Files.readString(root.resolve("database/bill-01-schema.sql"));
        String verifier = Files.readString(root.resolve("database/bill-01-verify.sql"));
        assertThat(migration).contains("audit_logs_event_type_check", "ck_bills_status", "ck_payment_records_amount");
        assertThat(verifier).contains("exact audit allowlist", "FOREIGN KEY", "BILL_GENERATED", "BILL_VOIDED");
    }

    @Test
    void runnerContainsFailClosedRecoveryGuards() throws Exception {
        String runner = Files.readString(root.resolve("scripts/migrate-hms-database.ps1"));
        assertThat(runner).contains("SHA-256 mismatch", "Migration stopped", "hms_migration.migration_ledger",
                "Catalog drift since the last recorded migration", "ON_ERROR_STOP=1");
        assertThat(runner).doesNotContain("pgpass.conf");
    }
}
