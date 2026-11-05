package com.finguard.copilot.governance;

import java.nio.charset.StandardCharsets;
import java.net.URI;
import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.model.port.ModelRequest;
import com.finguard.copilot.model.adapter.openai.CompatibleChatRequestPayload;

/** Reviewed byte-BPE upper bound, limited to the documented two-message text protocol. */
public final class DeepseekTextTokenBound {
    public static final String VERSION="deepseek-v41-text-byte-bound-v1";
    public static final String RECIPE_REVISION="935817c66f5d6e64838f419692545f06787e485f";
    public static final String TOKENIZER_SHA256="81f64d1248a68ce3663e07ab3ee48b851e5df0e32d27cb98e4c9a268151e8d99";
    public int upperBound(CopilotProperties.Model model, ModelRequest request) {
        return upperBound(model,request,false);
    }
    public static boolean fixtureEndpoint(CopilotProperties.Model model) {
        return "deepseek".equals(model.provider()) && "local-test-stub".equals(model.modelId()) &&
                URI.create("http://127.0.0.1:18765").equals(model.baseUrl()) && "/chat/completions".equals(model.chatCompletionsPath());
    }
    public int upperBound(CopilotProperties.Model model,ModelRequest request,boolean fixture) {
        if(fixture ? !fixtureEndpoint(model) : (!"deepseek".equals(model.provider()) || !"deepseek-flash".equals(model.modelId()) ||
                !URI.create("https://api.deepseek.com").equals(model.baseUrl()) || !"/chat/completions".equals(model.chatCompletionsPath()))
                )
            throw RunManifestService.rejected("input bound unavailable for model protocol");
        return wireUpperBound(model,request);
    }

    static int wireUpperBound(CopilotProperties.Model model, ModelRequest request) {
        try {
            // UTF8 bytes include every system/schema/user byte and JSON escaping.
            // Identity normalizer + byte BPE cannot expand the plain text token count.
            // 1024 additionally covers fixed two-role framing and JSON-object instructions.
            var encoder=StandardCharsets.UTF_8.newEncoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT).onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT);
            int bytes=encoder.encode(java.nio.CharBuffer.wrap(CompatibleChatRequestPayload.create(model,request).toString())).remaining();
            return Math.addExact(bytes,1024);
        } catch(com.fasterxml.jackson.core.JsonProcessingException | java.nio.charset.CharacterCodingException | ArithmeticException e) {
            throw RunManifestService.rejected("input bound could not be verified");
        }
    }
}
