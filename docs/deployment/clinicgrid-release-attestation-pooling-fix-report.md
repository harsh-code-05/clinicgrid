# ClinicGrid release attestation pooling fix

Date: 2026-11-20 IST. Local branch `release/phase-2c-deployment`, HEAD `e3ff80993bb08be9b146bc82423c7d1d20bf64c3`. The implementation is local and uncommitted.

## Root cause

Render deployment `dep-dbvm812fngtc73cnhf70` connected to PostgreSQL and failed while the startup attestation gate was executing `CREATE FUNCTION pg_temp.hms_catalog_digest()`. The existing Neon transaction pool could reuse a server backend that already held the temporary function. The deployment logs do not identify the earlier connection that created it. [Neon's connection pooling documentation](https://neon.com/docs/connect/connection-pooling#connection-pooling-in-transaction-mode)

The deployment log recorded this PostgreSQL error:

> ERROR: function "hms_catalog_digest" already exists with same argument types

The error maps to SQLSTATE `42723` (`duplicate_function`). The gate failed closed and blocked application startup. The [live pooler verification](#live-neon-187-transaction-pool) below records the result after the change.

## Implementation

[ReleaseAttestationGate.java](../../src/main/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationGate.java), lines 139–233, now hashes the raw packaged catalog SQL bytes as before and extracts the unchanged CTE and SELECT from those same verified bytes. It rejects an unexpected wrapper. The original resource SHA-256 remains `742639ffe90934bf4cc350cf327bc1716503cf74d51580e866f7a77b72a2d9ff`.

The gate runs one catalog digest query. The JDBC callback opens a read-only transaction, sets the original `pg_catalog` search path with `SET LOCAL`, executes the extracted SELECT and rolls back before restoring auto-commit. That keeps deparsing under the same visibility rules as the legacy SQL function and holds one server backend for the digest query. The original catalog object scope, row ordering, JSONB aggregation, UTF-8 encoding and SHA-256 expression are reused verbatim. The startup path neither creates nor calls a temporary function. The canonical migration SQL, manifest, ledger data, migration runners, production properties and pool configuration are unchanged.

The gate requires a positive, independently configured database OID and checks it against the connected database. It also checks PostgreSQL version, a matching `DEPLOYMENT` target, and all nine ordered ledger entries and checksums before validating the digest. It requires one well-formed digest row and compares it to the existing ledger fingerprint. The runtime privilege check must explicitly report that the runtime role is safe. The existing TEMP requirement and permanent DDL restrictions remain in place. Missing, malformed or mismatched attestation data still aborts startup. [Database README deployment requirements](../../database/README.md#roles-and-recovery-controls) · [ReleaseAttestationGate.java, lines 48–55](../../src/main/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationGate.java#L48)

## Files changed

- [ReleaseAttestationGate.java](../../src/main/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationGate.java) executes the verified query without function DDL.
- [ReleaseAttestationContractTest.java](../../src/test/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationContractTest.java) checks exact query extraction, the transaction and query sequence, rollback and digest-resource integrity.
- [ReleaseAttestationGateTest.java](../../src/test/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationGateTest.java) checks digest acceptance and fail-closed startup scenarios.
- [ReleaseAttestationPostgresPoolingTest.java](../../src/test/java/com/harsh/hospitalmanagement/config/safety/ReleaseAttestationPostgresPoolingTest.java) adds eight guarded tests for a nonce-marked local PostgreSQL fixture.
- [database/README.md](../../database/README.md) describes the new startup path and the unchanged runtime role policy.
- This report.

## Local verification

| Run | Passed | Failed | Errors | Skipped | Scope |
| --- | ---: | ---: | ---: | ---: | --- |
| ReleaseAttestationGateTest | 9 | 0 | 0 | 0 | Matching and changed digest, missing digest, bad OID, missing target, partial ledger and missing role result |
| ReleaseAttestationContractTest | 11 | 0 | 0 | 0 | Same canonical query body, one read-only transaction, rollback, checksum and role contract |
| ReleaseAttestationLifecycleIntegrationTest | 2 | 0 | 0 | 0 | Existing embedded Tomcat lifecycle fixture |
| Other focused database-safety and migration tests | 173 | 0 | 0 | 0 | Environment, URL and data source safety, test-target, manifest and negative migration contracts |
| ReleaseAttestationPostgresPoolingTest | 0 | 0 | 0 | 8 | Requires an opted-in disposable PostgreSQL fixture |
| Broad safe offline Maven run | 965 | 0 | 0 | 79 | 1,044 cases. Includes the focused tests and the eight skipped new PostgreSQL tests. |

Maven exited with code 0. The broad run excluded 21 live-provider cases, comprising the ten Neon database cases, ten Neon object-storage cases and one Cloudflare live smoke case. The Neon database fixture includes DDL and delete tests, and the object-storage fixture includes remote writes. The 79 Maven skips comprise 77 opt-in PostgreSQL cases and two Windows storage cases. The prior credential-free run discovered and skipped the ten legacy Neon database cases. Frontend files were unchanged.

The broad offline run used Java 21 and Maven 3.9.16 with the cached Mockito Java agent.

```powershell
mvn.cmd -o --batch-mode '-Dstyle.color=never' "-DargLine=-javaagent:C:/Users/Harsh/.m2/repository/org/mockito/mockito-core/5.23.0/mockito-core-5.23.0.jar" '-Dtest=*,!NeonProductionCompatibilityTest,!NeonObjectStorageIntegrationTest,!CloudflareLiveSmokeTest' test
```

The gate tests exercised wrong OID, missing OID, missing expected digest, digest mismatch and an absent matching deployment-target row. The latter simulates the query result when no row matches the live database identity and `DEPLOYMENT` purpose. The focused tests operated on controlled fixtures. The initial implementation run also passed executable JAR packaging and the packaged manifest/resource verifier.

### Final documentation verification

The offline Maven package run after the documentation corrections completed with 195 passed, nine skipped, zero failures, and zero errors. The skips cover eight pooling regressions and one validation test that require an approved disposable PostgreSQL fixture. The rebuilt executable JAR passed the packaging verifier. All 15 expected resources, comprising the manifest and 14 SQL files, matched the exact working-tree and base commit bytes.

## Live Neon 18.7 transaction pool

A standalone Java probe used the existing TLS transaction pooler for `clinicgrid-prod` / `production` with `sslmode=verify-full`. It validated the canonical resource checksum, extracted the exact query through the compiled gate method, and used the gate's transaction-local `pg_catalog` search path. Each transaction began with `BEGIN READ ONLY` and ended with `ROLLBACK`. It verified PostgreSQL 18.7, database `neondb`, OID `16397`, current and session role `hms_app_prod`, exactly one matching `DEPLOYMENT` target, and all nine ordered migration IDs and checksums. These checks and the digest ran on the same server backend within each transaction. [Neon transaction pooling](https://neon.com/docs/connect/connection-pooling#connection-pooling-in-transaction-mode) · [PostgreSQL read-only transactions](https://www.postgresql.org/docs/18/sql-begin.html)

| Live transactions | Server backend PIDs | Read-only confirmed | Digest matched ledger | Temporary catalog footprint |
| --- | --- | --- | --- | --- |
| Three sequential | 592, 592, 592 | 3/3 | 3/3 | Unchanged |
| Three overlapping on independent connections | 592, 3670, 3681 | 3/3 | 3/3 | Unchanged |

Each query returned `e0aa8569af28391ca8a68ed0f054fff49de823e1fb5b1ddf6f981579fb42e2b1`, exactly matching the existing authoritative ledger digest. This verifies parity for the live catalog against its existing attestation and repeatability on reused and distinct pooled backends. The backend with PID 592 already had one temporary routine, whose identity was outside the check. The probe compared the temporary namespace and counts of relations, routines and types before and after each query. It issued no DDL or DML and invoked no temporary function.

## Remaining verification scope

The eight local pooling regression cases compiled and skipped under the existing opt-in guard. No approved, nonce-marked disposable PostgreSQL fixture was available, and none was created. The cases cover legacy-helper parity with a representative catalog, an existing temporary function, repeated attestation on one backend, distinct Hikari backends, catalog drift, missing digest, wrong OID, wrong deployment purpose and permanent-DDL rejection for the restricted role. The live test establishes SQL compatibility with the production transaction pooler. Full Spring Boot startup against production, including `SpecializationInitializer`, was left outside the read-only probe. Fail-closed behavior was tested locally without changing live metadata.

The final documentation correction updated the database README, this report, and the local release manifest. The reviewed Java and SQL files are unchanged. Existing excluded files were preserved. The documentation verification did not access PostgreSQL, change environment variables, run migrations, deploy, or stage, commit, push, or merge Git changes.
