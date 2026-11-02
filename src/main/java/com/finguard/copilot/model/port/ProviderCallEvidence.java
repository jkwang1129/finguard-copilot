package com.finguard.copilot.model.port;

/** Correlation/usage observation only; no raw response, prompt or credential. */
public record ProviderCallEvidence(String analysisId,String provider,String providerRequestIdSha256,
        String returnedModelSha256,int httpStatus,Long reportedInputTokens,Long reportedOutputTokens,long latencyMillis) {}
