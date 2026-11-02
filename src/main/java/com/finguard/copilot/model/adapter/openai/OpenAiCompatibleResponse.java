package com.finguard.copilot.model.adapter.openai;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;

@JsonIgnoreProperties(ignoreUnknown = true)
record OpenAiCompatibleResponse(List<Choice> choices, Usage usage) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Choice(Message message) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Message(String content, @JsonProperty("tool_calls") JsonNode toolCalls,
                   @JsonProperty("function_call") JsonNode functionCall) {
        Message {
            if ((toolCalls != null && !toolCalls.isNull() && (!toolCalls.isArray() || !toolCalls.isEmpty()))
                    || (functionCall != null && !functionCall.isNull())) {
                throw new IllegalArgumentException("model tool calls are not supported");
            }
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Usage(
            @JsonProperty("prompt_tokens") Integer inputTokens,
            @JsonProperty("completion_tokens") Integer outputTokens
    ) {
    }
}
