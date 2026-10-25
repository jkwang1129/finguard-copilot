package com.finguard.copilot.analysis.application;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.CreatedAnalysis;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.IdempotencyReservation;
import com.finguard.copilot.api.analysis.AnalysisAcceptedResponse;
import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.context.FactSnapshot;
import com.finguard.copilot.context.SnapshotCodec;
import com.finguard.copilot.coreclient.port.CoreClient;
import com.finguard.copilot.coreclient.port.CoreClientException;
import com.finguard.copilot.coreclient.port.CurrentUser;
import com.finguard.copilot.coreclient.port.ReviewTaskContext;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.AccountSummary;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.Reconciliation;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.ReviewTask;
import com.finguard.copilot.coreclient.port.ReviewTaskContext.RiskFact;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InOrder;
import org.mockito.ArgumentCaptor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class CreateAnalysisServiceTest {

    private static final String TOKEN = "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiIxIn0.signature";
    private static final Instant CAPTURED_AT = Instant.parse("2026-08-25T02:03:04.123456Z");

    private final CoreClient coreClient = mock(CoreClient.class);
    private final AnalysisCreationStore creationStore = mock(AnalysisCreationStore.class);
    private final SnapshotCodec snapshotCodec = new SnapshotCodec();
    private CreateAnalysisService service;

    @BeforeEach
    void setUp() {
        service = new CreateAnalysisService(coreClient, snapshotCodec, creationStore);
    }

    @Test
    void checksReviewerBeforeLookupCapturesContextBeforeCreateAndReturnsAccepted() {
        ReviewTaskContext context = context(101L);
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer-a", Set.of("REVIEWER", "ADMIN")));
        when(creationStore.findReservation(eq("reviewer-a"), eq("same-key")))
                .thenReturn(Optional.empty());
        when(coreClient.reviewTaskContext(101L, TOKEN)).thenReturn(context);
        when(creationStore.create(any(), any(FactSnapshot.class), any(), any()))
                .thenReturn(new CreatedAnalysis(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"),
                        AnalysisStatus.CONTEXT_READY));

        AnalysisAcceptedResponse accepted = service.create(new CreateAnalysisCommand(101L, " same-key "), TOKEN);

        assertThat(accepted.analysisId()).isEqualTo(UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa"));
        assertThat(accepted.status()).isEqualTo(AnalysisStatus.CONTEXT_READY);
        assertThat(accepted.replayed()).isFalse();
        InOrder order = inOrder(coreClient, creationStore);
        order.verify(coreClient).currentUser(TOKEN);
        order.verify(creationStore).findReservation("reviewer-a", "same-key");
        order.verify(coreClient).reviewTaskContext(101L, TOKEN);
        order.verify(creationStore).create(any(), any(FactSnapshot.class), any(), any());
    }

    @Test
    void createUsesConfiguredSnapshotRetention() {
        CopilotProperties configured = new CopilotProperties(
                new CopilotProperties.Core(URI.create("https://core.example.internal"), null, null),
                new CopilotProperties.Embedding(8),
                new CopilotProperties.Retrieval(5),
                CopilotProperties.Dispatch.defaults(),
                new CopilotProperties.Jobs(false, 100, Duration.ofDays(7),
                        Duration.ofDays(7), Duration.ofHours(1)));
        CreateAnalysisService configuredService = new CreateAnalysisService(
                coreClient, snapshotCodec, creationStore, configured,
                Clock.fixed(CAPTURED_AT, java.time.ZoneOffset.UTC));
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer-a", Set.of("REVIEWER")));
        when(creationStore.findReservation("reviewer-a", "configured-retention"))
                .thenReturn(Optional.empty());
        when(coreClient.reviewTaskContext(101L, TOKEN)).thenReturn(context(101L));
        when(creationStore.create(any(), any(FactSnapshot.class), any(), any()))
                .thenReturn(new CreatedAnalysis(UUID.randomUUID(), AnalysisStatus.CONTEXT_READY));

        configuredService.create(
                new CreateAnalysisCommand(101L, "configured-retention"), TOKEN);

        ArgumentCaptor<FactSnapshot> snapshot = ArgumentCaptor.forClass(FactSnapshot.class);
        verify(creationStore).create(any(), snapshot.capture(), any(), any());
        assertThat(snapshot.getValue().expiresAt())
                .isEqualTo(CAPTURED_AT.plus(7, java.time.temporal.ChronoUnit.DAYS));
    }

    @Test
    void rejectsAdminBeforeContextCaptureOrAnyLocalLookup() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("admin", Set.of("ADMIN")));

        assertThatThrownBy(() -> service.create(new CreateAnalysisCommand(101L, "key"), TOKEN))
                .isInstanceOf(CopilotException.class)
                .hasMessage("Reviewer access is required")
                .extracting(exception -> ((CopilotException) exception).code())
                .isEqualTo(ErrorCode.FORBIDDEN);

        verify(creationStore, never()).findReservation(any(), any());
        verify(coreClient, never()).reviewTaskContext(any(Long.class), any());
        verify(creationStore, never()).create(any(), any(), any(), any());
    }

    @ParameterizedTest
    @EnumSource(value = CoreClientException.Kind.class, names = {"UNAUTHENTICATED", "FORBIDDEN", "NOT_FOUND", "UNAVAILABLE"})
    void mapsCoreFailuresWithoutWritingLocalRows(CoreClientException.Kind kind) {
        when(coreClient.currentUser(TOKEN)).thenThrow(new CoreClientException(kind, "upstream detail", null));

        assertThatThrownBy(() -> service.create(new CreateAnalysisCommand(101L, "key"), TOKEN))
                .isInstanceOf(CopilotException.class)
                .satisfies(exception -> {
                    ErrorCode code = ((CopilotException) exception).code();
                    if (kind == CoreClientException.Kind.UNAUTHENTICATED) {
                        assertThat(code).isEqualTo(ErrorCode.UNAUTHENTICATED);
                    } else if (kind == CoreClientException.Kind.FORBIDDEN) {
                        assertThat(code).isEqualTo(ErrorCode.FORBIDDEN);
                    } else if (kind == CoreClientException.Kind.NOT_FOUND) {
                        assertThat(code).isEqualTo(ErrorCode.RESOURCE_NOT_FOUND);
                    } else {
                        assertThat(code).isEqualTo(ErrorCode.SERVICE_UNAVAILABLE);
                    }
                });

        verify(creationStore, never()).findReservation(any(), any());
        verify(creationStore, never()).create(any(), any(), any(), any());
    }

    @Test
    void replaysMatchingActiveReservationWithoutCapturingContext() {
        UUID originalId = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");
        byte[] requestHash = requestHash(101L);
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer-a", Set.of("REVIEWER")));
        when(creationStore.findReservation("reviewer-a", "key"))
                .thenReturn(Optional.of(new AnalysisCreationStore.IdempotencyReservation(
                        originalId, requestHash, Instant.now().plusSeconds(60))));

        AnalysisAcceptedResponse accepted = service.create(new CreateAnalysisCommand(101L, "key"), TOKEN);

        assertThat(accepted.analysisId()).isEqualTo(originalId);
        assertThat(accepted.replayed()).isTrue();
        verify(coreClient, never()).reviewTaskContext(any(Long.class), any());
        verify(creationStore, never()).create(any(), any(), any(), any());
    }

    @Test
    void expiredReservationIsCompareDeletedAndRetriedWithoutReadingCoreTwice() {
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer-a", Set.of("REVIEWER")));
        when(creationStore.findReservation("reviewer-a", "key"))
                .thenReturn(Optional.of(new AnalysisCreationStore.IdempotencyReservation(
                        UUID.fromString("cccccccc-cccc-cccc-cccc-cccccccccccc"),
                        requestHash(101L), Instant.now().minusSeconds(1))));
        when(coreClient.reviewTaskContext(101L, TOKEN)).thenReturn(context(101L));
        when(creationStore.create(any(), any(FactSnapshot.class), any(), any()))
                .thenThrow(AnalysisCreationStore.AnalysisCreationException.conflict())
                .thenReturn(new CreatedAnalysis(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"),
                        AnalysisStatus.CONTEXT_READY));
        when(creationStore.deleteExpiredReservation(eq("reviewer-a"), eq("key"), any(), any()))
                .thenReturn(true);

        AnalysisAcceptedResponse accepted = service.create(new CreateAnalysisCommand(101L, "key"), TOKEN);

        assertThat(accepted.analysisId()).isEqualTo(UUID.fromString("dddddddd-dddd-dddd-dddd-dddddddddddd"));
        assertThat(accepted.replayed()).isFalse();
        verify(coreClient).reviewTaskContext(101L, TOKEN);
    }

    @Test
    void reloadsReplacementWhenCompareDeleteLosesRaceBeforeRetryingCreate() {
        UUID oldId = UUID.fromString("eeeeeeee-eeee-eeee-eeee-eeeeeeeeeeee");
        UUID replacementId = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff");
        IdempotencyReservation expired = new IdempotencyReservation(
                oldId, requestHash(101L), Instant.now().minusSeconds(1));
        IdempotencyReservation replacement = new IdempotencyReservation(
                replacementId, requestHash(101L), Instant.now().plusSeconds(60));
        when(coreClient.currentUser(TOKEN)).thenReturn(new CurrentUser("reviewer-a", Set.of("REVIEWER")));
        when(creationStore.findReservation("reviewer-a", "key"))
                .thenReturn(Optional.of(expired), Optional.of(expired), Optional.of(replacement));
        when(coreClient.reviewTaskContext(101L, TOKEN)).thenReturn(context(101L));
        when(creationStore.create(any(), any(FactSnapshot.class), any(), any()))
                .thenThrow(AnalysisCreationStore.AnalysisCreationException.conflict());
        when(creationStore.deleteExpiredReservation(
                eq("reviewer-a"), eq("key"), eq(oldId), any())).thenReturn(false);

        AnalysisAcceptedResponse accepted = service.create(
                new CreateAnalysisCommand(101L, "key"), TOKEN);

        assertThat(accepted.analysisId()).isEqualTo(replacementId);
        assertThat(accepted.replayed()).isTrue();
        verify(creationStore).create(any(), any(FactSnapshot.class), any(), any());
        verify(coreClient).reviewTaskContext(101L, TOKEN);
    }

    private static ReviewTaskContext context(long taskId) {
        return new ReviewTaskContext(
                new ReviewTask(taskId, 0, "PENDING", "HIGH_AMOUNT", "REQUIRES_REVIEW", CAPTURED_AT),
                new ReviewTaskContext.Transaction(202, 303, new BigDecimal("120000.00"), "CNY",
                        CAPTURED_AT, "CSV_IMPORT"),
                new Reconciliation("SUSPICIOUS", "AMOUNT_AND_TIME", "REQUIRES_REVIEW"),
                List.of(new RiskFact("HIGH_AMOUNT", "120000.00", "TRANSACTION")),
                new AccountSummary("ACTIVE", "Primary"), CAPTURED_AT);
    }

    private static byte[] requestHash(long reviewTaskId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("{\"reviewTaskId\":" + reviewTaskId + "}").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }
}
