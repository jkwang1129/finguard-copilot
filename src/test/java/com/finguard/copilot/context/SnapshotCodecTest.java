package com.finguard.copilot.context;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;

import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotCodecTest {

    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T01:02:03.456789Z");
    private static final String EXPECTED_CANONICAL_JSON = """
            {"accountSummary":{"displayName":"Primary","status":"ACTIVE"},"capturedAt":"2026-08-25T01:02:03.456789Z","reconciliation":{"matchMethod":"AMOUNT_AND_TIME","reasonCode":"REQUIRES_REVIEW","resultType":"SUSPICIOUS"},"reviewTask":{"createdAt":"2026-08-24T09:10:11.123456Z","id":101,"reasonCode":"AMOUNT_THRESHOLD_EXCEEDED","ruleCode":"HIGH_AMOUNT","status":"PENDING","version":0},"riskFacts":[{"code":"A_CODE","source":"RULE","value":"A_VALUE"},{"code":"Z_CODE","source":"TRANSACTION","value":"Z_VALUE"}],"transaction":{"accountId":303,"amount":120000,"currency":"CNY","id":202,"occurredAt":"2026-08-24T09:05:00.654321Z","sourceType":"CSV_IMPORT"}}""";

    private final SnapshotCodec codec = new SnapshotCodec();

    @Test
    void canonicalBytesAndSha256AreStableDespiteRiskFactOrder() throws Exception {
        FactSnapshot forward = codec.capture(context(List.of(
                new RiskFact("A_CODE", "A_VALUE", "RULE"),
                new RiskFact("Z_CODE", "Z_VALUE", "TRANSACTION")
        )));
        FactSnapshot reversed = codec.capture(context(List.of(
                new RiskFact("Z_CODE", "Z_VALUE", "TRANSACTION"),
                new RiskFact("A_CODE", "A_VALUE", "RULE")
        )));

        byte[] expectedBytes = EXPECTED_CANONICAL_JSON.getBytes(StandardCharsets.UTF_8);
        byte[] expectedHash = MessageDigest.getInstance("SHA-256").digest(expectedBytes);

        assertThat(codec.canonicalBytes(forward)).isEqualTo(expectedBytes);
        assertThat(codec.canonicalBytes(reversed)).isEqualTo(expectedBytes);
        assertThat(forward.sha256()).isEqualTo(expectedHash);
        assertThat(reversed.sha256()).isEqualTo(expectedHash);
        assertThat(HexFormat.of().formatHex(forward.sha256()))
                .isEqualTo(HexFormat.of().formatHex(expectedHash));
    }

    @Test
    void exactDecimalCanonicalBytesAndLegacyNumericSnapshotStillVerify() throws Exception {
        byte[] legacy=EXPECTED_CANONICAL_JSON.getBytes(StandardCharsets.UTF_8);
        byte[] legacyHash=MessageDigest.getInstance("SHA-256").digest(legacy);
        var facts=codec.decodeAndVerify(legacy,legacyHash);
        var legacySnapshot=new FactSnapshot(1,facts,facts.capturedAt(),facts.capturedAt().plus(Duration.ofDays(30)),legacyHash);
        assertThat(codec.canonicalBytes(legacySnapshot)).isEqualTo(legacy);
        var transaction=facts.transaction();
        var precise=new ReviewTaskContext(facts.reviewTask(),new ReviewTaskContext.Transaction(transaction.id(),transaction.accountId(),
                new BigDecimal("99.999999999999999999"),transaction.currency(),transaction.occurredAt(),transaction.sourceType()),
                facts.reconciliation(),facts.riskFacts(),facts.accountSummary(),facts.capturedAt());
        var snapshot=codec.capture(precise);
        assertThat(new String(codec.canonicalBytes(snapshot),StandardCharsets.UTF_8))
                .contains("\"amount\":99.999999999999999999");
        assertThat(codec.decodeAndVerify(codec.canonicalBytes(snapshot),snapshot.sha256()).transaction().amount())
                .isEqualByComparingTo("99.999999999999999999");
        assertThatThrownBy(()->codec.decodeAndVerify(EXPECTED_CANONICAL_JSON.replace("120000","120001")
                .getBytes(StandardCharsets.UTF_8),legacyHash)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void captureUsesSchemaOneAndExpiresExactlyThirtyDaysAfterCapture() {
        FactSnapshot snapshot = codec.capture(context(List.of(
                new RiskFact("A_CODE", "A_VALUE", "RULE")
        )));

        assertThat(snapshot.schemaVersion()).isEqualTo(1);
        assertThat(snapshot.capturedAt()).isEqualTo(CAPTURED_AT);
        assertThat(snapshot.expiresAt()).isEqualTo(CAPTURED_AT.plus(30, ChronoUnit.DAYS));
    }

    @Test
    void captureUsesConfiguredSnapshotRetention() {
        FactSnapshot snapshot = codec.capture(
                context(List.of(new RiskFact("A_CODE", "A_VALUE", "RULE"))),
                Duration.ofDays(7));

        assertThat(snapshot.expiresAt()).isEqualTo(CAPTURED_AT.plus(7, ChronoUnit.DAYS));
    }

    @Test
    void decodeRecreatesImmutableNormalizedPortFacts() {
        FactSnapshot snapshot = codec.capture(context(List.of(
                new RiskFact("Z_CODE", "Z_VALUE", "TRANSACTION"),
                new RiskFact("A_CODE", "A_VALUE", "RULE")
        )));

        ReviewTaskContext decoded = codec.decode(codec.canonicalBytes(snapshot));

        assertThat(decoded).isEqualTo(snapshot.facts());
        assertThat(decoded.riskFacts()).containsExactly(
                new RiskFact("A_CODE", "A_VALUE", "RULE"),
                new RiskFact("Z_CODE", "Z_VALUE", "TRANSACTION")
        );
        assertThatThrownBy(() -> decoded.riskFacts().add(new RiskFact("X", "Y", "Z")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void captureTruncatesAllSnapshotInstantsToMicrosecondsBeforeHashing() {
        Instant capturedAt = Instant.parse("2026-08-25T01:02:03.456789987Z");
        Instant taskCreatedAt = Instant.parse("2026-08-24T09:10:11.123456789Z");
        Instant occurredAt = Instant.parse("2026-08-24T09:05:00.654321999Z");
        FactSnapshot snapshot = codec.capture(context(
                List.of(new RiskFact("A_CODE", "A_VALUE", "RULE")),
                capturedAt,
                taskCreatedAt,
                occurredAt
        ));

        assertThat(snapshot.capturedAt())
                .isEqualTo(Instant.parse("2026-08-25T01:02:03.456789Z"));
        assertThat(snapshot.expiresAt())
                .isEqualTo(Instant.parse("2026-09-24T01:02:03.456789Z"));
        assertThat(snapshot.facts().capturedAt()).isEqualTo(snapshot.capturedAt());
        assertThat(snapshot.facts().reviewTask().createdAt())
                .isEqualTo(Instant.parse("2026-08-24T09:10:11.123456Z"));
        assertThat(snapshot.facts().transaction().occurredAt())
                .isEqualTo(Instant.parse("2026-08-24T09:05:00.654321Z"));
        assertThat(new String(codec.canonicalBytes(snapshot), StandardCharsets.UTF_8))
                .contains(
                        "\"capturedAt\":\"2026-08-25T01:02:03.456789Z\"",
                        "\"createdAt\":\"2026-08-24T09:10:11.123456Z\"",
                        "\"occurredAt\":\"2026-08-24T09:05:00.654321Z\"")
                .doesNotContain("456789987", "123456789", "654321999");
    }

    @Test
    void decodeAndVerifyAcceptsMatchingChecksumAndRejectsChangedFactOrHash() {
        FactSnapshot snapshot = codec.capture(context(List.of(
                new RiskFact("A_CODE", "A_VALUE", "RULE")
        )));
        byte[] storedJson = codec.canonicalBytes(snapshot);
        byte[] changedFact = new String(storedJson, StandardCharsets.UTF_8)
                .replace("120000", "120001")
                .getBytes(StandardCharsets.UTF_8);
        byte[] changedHash = snapshot.sha256();
        changedHash[0] ^= 1;

        assertThat(codec.decodeAndVerify(storedJson, snapshot.sha256()))
                .isEqualTo(snapshot.facts());
        assertThatThrownBy(() -> codec.decodeAndVerify(changedFact, snapshot.sha256()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("snapshot checksum verification failed")
                .hasNoCause();
        assertThatThrownBy(() -> codec.decodeAndVerify(storedJson, changedHash))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("snapshot checksum verification failed")
                .hasNoCause();
    }

    @Test
    void factSnapshotDefensivelyCopiesChecksumOnInputAndOutput() {
        FactSnapshot captured = codec.capture(context(List.of(
                new RiskFact("A_CODE", "A_VALUE", "RULE")
        )));
        byte[] checksum = captured.sha256();
        FactSnapshot copied = new FactSnapshot(
                captured.schemaVersion(),
                captured.facts(),
                captured.capturedAt(),
                captured.expiresAt(),
                checksum
        );
        byte[] expected = copied.sha256();

        Arrays.fill(checksum, (byte) 0);
        byte[] firstRead = copied.sha256();
        Arrays.fill(firstRead, (byte) 1);

        assertThat(copied.sha256()).isEqualTo(expected);
        assertThat(copied.sha256()).isNotSameAs(firstRead);
    }

    @Test
    void factSnapshotUsesChecksumContentForEqualityAndHashCode() {
        FactSnapshot captured = codec.capture(context(List.of(
                new RiskFact("A_CODE", "A_VALUE", "RULE")
        )));
        FactSnapshot equal = new FactSnapshot(
                captured.schemaVersion(),
                captured.facts(),
                captured.capturedAt(),
                captured.expiresAt(),
                Arrays.copyOf(captured.sha256(), captured.sha256().length)
        );
        byte[] differentHash = captured.sha256();
        differentHash[0] ^= 1;
        FactSnapshot unequal = new FactSnapshot(
                captured.schemaVersion(),
                captured.facts(),
                captured.capturedAt(),
                captured.expiresAt(),
                differentHash
        );
        HashSet<FactSnapshot> snapshots = new HashSet<>();
        snapshots.add(captured);
        snapshots.add(equal);
        snapshots.add(unequal);

        assertThat(equal).isEqualTo(captured);
        assertThat(equal.hashCode()).isEqualTo(captured.hashCode());
        assertThat(unequal).isNotEqualTo(captured);
        assertThat(snapshots).hasSize(2);
    }

    @Test
    void snapshotBoundaryExposesNoStringAuthorizationOrTokenParameter() {
        List<Class<?>> boundaryTypes = List.of(FactSnapshot.class, SnapshotCodec.class);

        for (Class<?> boundaryType : boundaryTypes) {
            for (Constructor<?> constructor : boundaryType.getDeclaredConstructors()) {
                assertThat(Arrays.asList(constructor.getParameterTypes()))
                        .as("constructor parameters for %s", boundaryType.getSimpleName())
                        .doesNotContain(String.class);
            }
            for (Method method : boundaryType.getDeclaredMethods()) {
                if (Modifier.isPublic(method.getModifiers())) {
                    assertThat(Arrays.asList(method.getParameterTypes()))
                            .as("public method parameters for %s.%s",
                                    boundaryType.getSimpleName(), method.getName())
                            .doesNotContain(String.class);
                }
            }
        }
    }

    private static ReviewTaskContext context(List<RiskFact> riskFacts) {
        return context(
                riskFacts,
                CAPTURED_AT,
                Instant.parse("2026-08-24T09:10:11.123456Z"),
                Instant.parse("2026-08-24T09:05:00.654321Z")
        );
    }

    private static ReviewTaskContext context(
            List<RiskFact> riskFacts,
            Instant capturedAt,
            Instant taskCreatedAt,
            Instant occurredAt
    ) {
        return new ReviewTaskContext(
                new ReviewTask(
                        101,
                        0,
                        "PENDING",
                        "HIGH_AMOUNT",
                        "AMOUNT_THRESHOLD_EXCEEDED",
                        taskCreatedAt
                ),
                new ReviewTaskContext.Transaction(
                        202,
                        303,
                        new BigDecimal("120000.00"),
                        "CNY",
                        occurredAt,
                        "CSV_IMPORT"
                ),
                new Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
                riskFacts,
                new AccountSummary("ACTIVE", "Primary"),
                capturedAt
        );
    }
}
