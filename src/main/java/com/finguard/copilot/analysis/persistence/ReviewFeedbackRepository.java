package com.finguard.copilot.analysis.persistence;

import java.util.UUID;

import com.finguard.copilot.common.error.CopilotException;
import com.finguard.copilot.common.error.ErrorCode;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

@Repository
public class ReviewFeedbackRepository {
    private final JdbcTemplate jdbc;

    public ReviewFeedbackRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String submit(UUID analysisId, String reviewer, String verdict) {
        byte[] id = JdbcAnalysisRunRepository.uuidBytes(analysisId);
        try {
            int inserted = jdbc.update("""
                    INSERT INTO review_feedback (analysis_id, reviewer_username, verdict)
                    SELECT id, reviewer_username, ? FROM analysis_run
                    WHERE id = ? AND BINARY reviewer_username = BINARY ? AND status = 'SUCCEEDED'
                    """, verdict, id, reviewer);
            if (inserted == 1) {
                return verdict;
            }
            throw new CopilotException(ErrorCode.CONFLICT, "Only completed owned analyses accept feedback");
        } catch (DuplicateKeyException duplicate) {
            String existing = jdbc.query("""
                    SELECT verdict FROM review_feedback
                    WHERE analysis_id = ? AND BINARY reviewer_username = BINARY ?
                    """, rs -> rs.next() ? rs.getString(1) : null, id, reviewer);
            if (verdict.equals(existing)) {
                return verdict;
            }
            throw new CopilotException(ErrorCode.CONFLICT, "Feedback has already been submitted");
        }
    }
}
