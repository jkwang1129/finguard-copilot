package com.finguard.copilot.analysis.persistence;

import java.time.Instant;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import com.finguard.copilot.analysis.domain.AnalysisRun;
import com.finguard.copilot.analysis.domain.AnalysisStatus;

public interface AnalysisRunRepository {

    int insert(AnalysisRun run);

    Optional<AnalysisView> findByIdAndReviewer(UUID analysisId, String reviewerUsername);

    Optional<WorkView> findForWork(UUID analysisId);

    boolean hasReceipt(UUID eventId);

    int updateStatus(UUID analysisId, AnalysisStatus expected, AnalysisStatus next,
                     String failureCode, Instant updatedAt);

    int insertResult(UUID analysisId, int schemaVersion, String recommendation,
                     String resultJson, Instant createdAt);

    int insertReceipt(UUID eventId, UUID analysisId, Instant receivedAt);

    record AnalysisView(
            UUID analysisId,
            AnalysisStatus status,
            String failureCode,
            String resultJson
    ) {

        public AnalysisView {
            if (analysisId == null || status == null) {
                throw new IllegalArgumentException("analysis view identifiers must not be null");
            }
            if (status == AnalysisStatus.FAILED
                    && (failureCode == null || failureCode.isBlank() || failureCode.length() > 64)) {
                throw new IllegalArgumentException("failed analysis must contain a bounded failure code");
            }
            if (status != AnalysisStatus.FAILED && failureCode != null) {
                throw new IllegalArgumentException("non-failed analysis must not contain a failure code");
            }
            if (status == AnalysisStatus.SUCCEEDED && (resultJson == null || resultJson.isBlank())) {
                throw new IllegalArgumentException("succeeded analysis must contain a result");
            }
        }
    }

    record WorkView(
            UUID analysisId,
            AnalysisStatus status,
            String failureCode,
            String factsJson,
            byte[] factsSha256,
            String resultJson
    ) {

        public WorkView {
            if (analysisId == null || status == null) {
                throw new IllegalArgumentException("work view identifiers must not be null");
            }
            if (factsJson == null || factsJson.isBlank()
                    || factsSha256 == null || factsSha256.length != 32) {
                throw new IllegalArgumentException("work view snapshot must be present");
            }
            factsSha256 = factsSha256.clone();
        }

        @Override
        public byte[] factsSha256() {
            return factsSha256.clone();
        }

        @Override
        public boolean equals(Object candidate) {
            return candidate == this
                    || candidate instanceof WorkView other
                    && analysisId.equals(other.analysisId)
                    && status == other.status
                    && Objects.equals(failureCode, other.failureCode)
                    && Objects.equals(factsJson, other.factsJson)
                    && Arrays.equals(factsSha256, other.factsSha256)
                    && Objects.equals(resultJson, other.resultJson);
        }

        @Override
        public int hashCode() {
            return 31 * Objects.hash(analysisId, status, failureCode, factsJson, resultJson)
                    + Arrays.hashCode(factsSha256);
        }
    }
}
