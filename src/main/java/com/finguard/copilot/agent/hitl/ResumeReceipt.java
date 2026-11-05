package com.finguard.copilot.agent.hitl;
import java.util.UUID;
import com.finguard.copilot.analysis.domain.AnalysisStatus;
public record ResumeReceipt(UUID analysisId,
    @com.fasterxml.jackson.databind.annotation.JsonSerialize(using=com.fasterxml.jackson.databind.ser.std.ToStringSerializer.class)
    @io.swagger.v3.oas.annotations.media.Schema(type="string",pattern="^[0-9]+$")
    long revision,AnalysisStatus status,boolean replayed) {}
