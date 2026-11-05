package com.finguard.copilot.governance;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;

import com.finguard.copilot.config.CopilotProperties;
import com.finguard.copilot.config.SecretValue;

/** Selects one review gateway before the worker can claim or send a model call. */
public final class ReviewModelRoute {
    public static final String BACKUP_MODEL = "glm-5.2";
    private static final URI BACKUP_ENDPOINT = URI.create("https://open.bigmodel.cn/api/paas/v4");
    private static final String CHAT_PATH = "/chat/completions";
    static final String GLM_ENCODING_REVISION = "cf457fa734ab149ffef225f80893eb38c6ff5cdc";
    static final String GLM_ENCODING_SHA256 = "19e773648cb4e65de8660ea6365e10acca112d42a854923df93db4a6f333a82d";
    static final String EVALUATION_SHA256 = "5a61057d585bcf08f34adad3e9fabe7643fe9926899a9feb2522e883a9d3e416";
    static final String PROMPT_AND_SCHEMA_SHA256 = "5ea00fed1f4c52449c3a82bb3dffd18088968afff46f4602de5fe924cd5f5337";

    private ReviewModelRoute() {}

    public static CopilotProperties.Model select(CopilotProperties.Model primary, String promptVersion,
            boolean primaryUnavailable, boolean fixtureOrAgent, SecretValue backupKey) {
        if (!primaryUnavailable) return primary;
        if (fixtureOrAgent || !"deepseek".equals(primary.provider()) || !"deepseek-flash".equals(primary.modelId())
                || !URI.create("https://api.deepseek.com").equals(primary.baseUrl())
                || !CHAT_PATH.equals(primary.chatCompletionsPath())
                || !"review-analysis-v2".equals(promptVersion) || !backupKey.isPresent()) {
            throw RunManifestService.rejected("review backup is unavailable for this configuration");
        }
        return new CopilotProperties.Model("zhipu", BACKUP_MODEL, BACKUP_ENDPOINT, backupKey, CHAT_PATH,
                primary.inputTokenLimit(), primary.outputTokenLimit(), Duration.ofSeconds(20),
                "zhipu-glm52-2026-11-05-no-cache", "CNY", new BigDecimal("8"), new BigDecimal("28"));
    }

    public static boolean isQualifiedBackup(CopilotProperties.Model model) {
        return "zhipu".equals(model.provider()) && BACKUP_MODEL.equals(model.modelId())
                && BACKUP_ENDPOINT.equals(model.baseUrl()) && CHAT_PATH.equals(model.chatCompletionsPath());
    }
}
