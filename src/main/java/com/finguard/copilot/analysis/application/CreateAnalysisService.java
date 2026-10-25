package com.finguard.copilot.analysis.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.finguard.copilot.analysis.domain.AnalysisStatus;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.AnalysisCreationException;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.CreatedAnalysis;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.IdempotencyReservation;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.NewAnalysis;
import com.finguard.copilot.analysis.persistence.AnalysisCreationStore.OutboxRecord;
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
@Service
public class CreateAnalysisService {

    private static final String REVIEWER_ROLE = "REVIEWER";
    private static final long IDEMPOTENCY_HOURS = 24;
    private static final UUID EVENT_NAMESPACE = UUID.fromString("00000000-0000-0000-0000-000000000001");

    private final CoreClient coreClient;
    private final SnapshotCodec snapshotCodec;
    private final AnalysisCreationStore creationStore;
    private final Clock clock;
    private final Duration snapshotRetention;

    public CreateAnalysisService(
            CoreClient coreClient,
            SnapshotCodec snapshotCodec,
            AnalysisCreationStore creationStore
    ) {
        this(coreClient, snapshotCodec, creationStore, Clock.systemUTC());
    }

    @Autowired
    public CreateAnalysisService(
            CoreClient coreClient,
            SnapshotCodec snapshotCodec,
            AnalysisCreationStore creationStore,
            CopilotProperties properties
    ) {
        this(coreClient, snapshotCodec, creationStore, properties, Clock.systemUTC());
    }

    CreateAnalysisService(
            CoreClient coreClient,
            SnapshotCodec snapshotCodec,
            AnalysisCreationStore creationStore,
            Clock clock
    ) {
        this(coreClient, snapshotCodec, creationStore, clock,
                SnapshotCodec.DEFAULT_SNAPSHOT_RETENTION);
    }

    CreateAnalysisService(
            CoreClient coreClient,
            SnapshotCodec snapshotCodec,
            AnalysisCreationStore creationStore,
            CopilotProperties properties,
            Clock clock
    ) {
        this(coreClient, snapshotCodec, creationStore, clock,
                Objects.requireNonNull(properties, "properties must not be null")
                        .jobs().snapshotRetention());
    }

    private CreateAnalysisService(
            CoreClient coreClient,
            SnapshotCodec snapshotCodec,
            AnalysisCreationStore creationStore,
            Clock clock,
            Duration snapshotRetention
    ) {
        this.coreClient = coreClient;
        this.snapshotCodec = snapshotCodec;
        this.creationStore = creationStore;
        this.clock = clock;
        this.snapshotRetention = Objects.requireNonNull(
                snapshotRetention, "snapshotRetention must not be null");
    }

    public AnalysisAcceptedResponse create(CreateAnalysisCommand command, String bearerToken) {
        if (command == null) {
            throw new CopilotException(ErrorCode.INVALID_REQUEST, "The analysis request is required");
        }
        CurrentUser currentUser = currentUser(bearerToken);
        requireReviewer(currentUser);
        String idempotencyKey = normalizeIdempotencyKey(command.idempotencyKey());
        byte[] requestHash = requestHash(command.reviewTaskId());
        Instant now = SnapshotCodec.toMicrosecondPrecision(Instant.now(clock));

        Optional<IdempotencyReservation> reservation = creationStore.findReservation(
                currentUser.username(), idempotencyKey);
        if (reservation.isPresent() && reservation.get().expiresAt().isAfter(now)) {
            return replayOrConflict(reservation.get(), requestHash);
        }

        ReviewTaskContext context = reviewTaskContext(command.reviewTaskId(), bearerToken);
        FactSnapshot snapshot = snapshotCodec.capture(context, snapshotRetention);
        UUID analysisId = UUID.randomUUID();
        Instant expiresAt = now.plus(IDEMPOTENCY_HOURS, ChronoUnit.HOURS);
        NewAnalysis analysis = new NewAnalysis(
                analysisId,
                currentUser.username(),
                command.reviewTaskId(),
                context.reviewTask().version(),
                idempotencyKey,
                requestHash,
                now
        );
        OutboxRecord event = new OutboxRecord(
                UUID.nameUUIDFromBytes((EVENT_NAMESPACE + ":" + analysisId).getBytes(StandardCharsets.UTF_8)),
                analysisId,
                1,
                now,
                now
        );

        for (int attempt = 0; attempt < 2; attempt++) {
            try {
                CreatedAnalysis created = creationStore.create(analysis, snapshot, event, expiresAt);
                return new AnalysisAcceptedResponse(created.analysisId(), created.status(), false);
            } catch (AnalysisCreationException exception) {
                if (exception.kind() != AnalysisCreationException.Kind.CONFLICT) {
                    throw new CopilotException(ErrorCode.INTERNAL_ERROR,
                            "Analysis could not be persisted");
                }
                Optional<IdempotencyReservation> winner = creationStore.findReservation(
                        currentUser.username(), idempotencyKey);
                if (winner.isPresent() && winner.get().expiresAt().isAfter(now)) {
                    return replayOrConflict(winner.get(), requestHash);
                }
                if (winner.isPresent() && creationStore.deleteExpiredReservation(
                        currentUser.username(), idempotencyKey,
                        winner.get().analysisId(), winner.get().expiresAt())) {
                    continue;
                }
                Optional<IdempotencyReservation> reloadedWinner = creationStore.findReservation(
                        currentUser.username(), idempotencyKey);
                if (reloadedWinner.isPresent()
                        && reloadedWinner.get().expiresAt().isAfter(now)) {
                    return replayOrConflict(reloadedWinner.get(), requestHash);
                }
                if (attempt == 0) {
                    continue;
                }
            }
        }
        throw new CopilotException(ErrorCode.CONFLICT,
                "The idempotency key is already active for a different request");
    }

    private AnalysisAcceptedResponse replayOrConflict(
            IdempotencyReservation reservation,
            byte[] requestHash
    ) {
        if (!MessageDigest.isEqual(reservation.requestHash(), requestHash)) {
            throw new CopilotException(ErrorCode.CONFLICT,
                    "The idempotency key is already active for a different request");
        }
        return new AnalysisAcceptedResponse(
                reservation.analysisId(), reservation.status(), true);
    }

    private CurrentUser currentUser(String bearerToken) {
        try {
            return coreClient.currentUser(bearerToken);
        } catch (CoreClientException exception) {
            throw mappedCoreException(exception);
        }
    }

    private ReviewTaskContext reviewTaskContext(long reviewTaskId, String bearerToken) {
        try {
            return coreClient.reviewTaskContext(reviewTaskId, bearerToken);
        } catch (CoreClientException exception) {
            throw mappedCoreException(exception);
        }
    }

    private static void requireReviewer(CurrentUser currentUser) {
        if (currentUser == null || !currentUser.roles().contains(REVIEWER_ROLE)) {
            throw new CopilotException(ErrorCode.FORBIDDEN, "Reviewer access is required");
        }
    }

    private static String normalizeIdempotencyKey(String key) {
        if (key == null) {
            throw new CopilotException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must contain 1 to 100 visible ASCII characters");
        }
        String normalized = key.trim();
        if (normalized.length() < 1 || normalized.length() > 100
                || normalized.chars().anyMatch(value -> value < 0x21 || value > 0x7E)) {
            throw new CopilotException(ErrorCode.INVALID_REQUEST,
                    "Idempotency-Key must contain 1 to 100 visible ASCII characters");
        }
        return normalized;
    }

    private static byte[] requestHash(long reviewTaskId) {
        try {
            return MessageDigest.getInstance("SHA-256")
                    .digest(("{\"reviewTaskId\":" + reviewTaskId + "}")
                            .getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static CopilotException mappedCoreException(CoreClientException exception) {
        return switch (exception.kind()) {
            case UNAUTHENTICATED -> new CopilotException(
                    ErrorCode.UNAUTHENTICATED, "Core authentication is required");
            case FORBIDDEN -> new CopilotException(
                    ErrorCode.FORBIDDEN, "Core access is forbidden");
            case NOT_FOUND -> new CopilotException(
                    ErrorCode.RESOURCE_NOT_FOUND, "The requested Core resource was not found");
            case UNAVAILABLE, INVALID_RESPONSE -> new CopilotException(
                    ErrorCode.SERVICE_UNAVAILABLE, "Core service is unavailable");
        };
    }
}
