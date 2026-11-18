package com.harsh.hospitalmanagement.config.safety;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

class MigrationManifestTest {
    private static final Pattern ENTRY = Pattern.compile(
            "\\{ \\\"version\\\": (\\d+), \\\"id\\\": \\\"([^\\\"]+)\\\", \\\"path\\\": \\\"([^\\\"]+)\\\", \\\"sha256\\\": \\\"([0-9a-f]{64})\\\"");
    private static final Pattern AUXILIARY = Pattern.compile(
            "\\\"(catalogDigest|releaseVerifier)\\\"\\s*:\\s*\\{\\s*\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"\\s*\\}");
    private static final Pattern VERIFIER = Pattern.compile(
            "\\\"verifier\\\"\\s*:\\s*\\{\\s*\\\"path\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"\\s*,\\s*\\\"sha256\\\"\\s*:\\s*\\\"([0-9a-f]{64})\\\"\\s*\\}");

    @Test
    void manifestContainsTheCanonicalOrderAndMatchingChecksums() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String manifest = Files.readString(root.resolve("database/migration-manifest.json"));
        String[] ids = {"BASELINE", "SCH-02", "DISCO-01", "DISCO-02", "DOC-01", "AI-01", "AI-02", "APPT-CANCEL-01", "BILL-01"};
        Matcher matcher = ENTRY.matcher(manifest);
        int index = 0;
        while (matcher.find()) {
            assertThat(Integer.parseInt(matcher.group(1))).isEqualTo(index + 1);
            assertThat(matcher.group(2)).isEqualTo(ids[index]);
            Path sql = root.resolve(matcher.group(3));
            byte[] rawBytes = Files.readAllBytes(sql);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes));
            assertThat(checksum).isEqualTo(matcher.group(4));
            index++;
        }
        assertThat(index).isEqualTo(ids.length);

        Matcher auxMatcher = AUXILIARY.matcher(manifest);
        int auxCount = 0;
        while (auxMatcher.find()) {
            Path auxSql = root.resolve(auxMatcher.group(2));
            byte[] rawBytes = Files.readAllBytes(auxSql);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes));
            assertThat(checksum).isEqualTo(auxMatcher.group(3));
            auxCount++;
        }
        assertThat(auxCount).isEqualTo(2);

        Matcher verifierMatcher = VERIFIER.matcher(manifest);
        int verifierCount = 0;
        while (verifierMatcher.find()) {
            Path verifierSql = root.resolve(verifierMatcher.group(1));
            byte[] rawBytes = Files.readAllBytes(verifierSql);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(rawBytes));
            assertThat(checksum).isEqualTo(verifierMatcher.group(2));
            verifierCount++;
        }
        assertThat(verifierCount).isEqualTo(3);
    }

    @Test
    void exactByteSensitivityRejectsCrlfEquivalent() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String manifest = Files.readString(root.resolve("database/migration-manifest.json"));
        Matcher matcher = ENTRY.matcher(manifest);
        while (matcher.find()) {
            Path sql = root.resolve(matcher.group(3));
            byte[] rawBytes = Files.readAllBytes(sql);
            String lfContent = new String(rawBytes, StandardCharsets.UTF_8);
            assertThat(lfContent).doesNotContain("\r\n");
            byte[] crlfBytes = lfContent.replace("\n", "\r\n").getBytes(StandardCharsets.UTF_8);
            String crlfChecksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(crlfBytes));
            assertThat(crlfChecksum).isNotEqualTo(matcher.group(4));
        }
    }

    @Test
    void exactByteSensitivityRejectsTrailingByte() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String manifest = Files.readString(root.resolve("database/migration-manifest.json"));
        Matcher matcher = ENTRY.matcher(manifest);
        while (matcher.find()) {
            Path sql = root.resolve(matcher.group(3));
            byte[] rawBytes = Files.readAllBytes(sql);
            byte[] withTrailing = new byte[rawBytes.length + 1];
            System.arraycopy(rawBytes, 0, withTrailing, 0, rawBytes.length);
            withTrailing[rawBytes.length] = '\n';
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(withTrailing));
            assertThat(checksum).isNotEqualTo(matcher.group(4));
        }
    }

    @Test
    void exactByteSensitivityRejectsModifiedByte() throws Exception {
        Path root = Path.of("").toAbsolutePath();
        String manifest = Files.readString(root.resolve("database/migration-manifest.json"));
        Matcher matcher = ENTRY.matcher(manifest);
        while (matcher.find()) {
            Path sql = root.resolve(matcher.group(3));
            byte[] rawBytes = Files.readAllBytes(sql);
            byte[] modified = rawBytes.clone();
            modified[0] = (byte) (modified[0] ^ 0x01);
            String checksum = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(modified));
            assertThat(checksum).isNotEqualTo(matcher.group(4));
        }
    }

    @Test
    void crossComponentSha256AlgorithmConsistency() throws Exception {
        byte[] input = "HMS-EXACT-BYTE-VERIFICATION".getBytes(StandardCharsets.UTF_8);
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(input));
        assertThat(digest).matches("^[0-9a-f]{64}$");
        assertThat(digest).isEqualTo("e140f512f402e434e103b701c7a4dcb2edbc8c2f7eb0e5a8b396a5658cc09181");
    }
}
