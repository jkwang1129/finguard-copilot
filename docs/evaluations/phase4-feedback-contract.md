# P4-2 审核反馈契约 v1

`PUT /api/copilot/review-analyses/{analysisId}/feedback`，请求体仅允许 `{"verdict":"AGREE"}`、`DISAGREE` 或 `UNSURE`。响应只包含 `analysisId` 和 `verdict`；无自由文本、JWT、模型原文或客户端 reviewer/task ID。

服务端用 Bearer Token 调 Core 只读 `currentUser`，要求 `REVIEWER`，再按 `analysisId + reviewer_username` 精确字节匹配查询归属（避免 MySQL 默认不区分大小写/重音的排序规则混淆身份）；未拥有或不存在均返回 404，未完成分析返回 409。只有 `SUCCEEDED` 可提交。数据库 V7 以 `analysis_id` 为主键，单条 `INSERT ... SELECT` 再验证归属与状态；并发同值提交视为重放，不同值返回 409。反馈不可修改。无自动删除期限；上线前须确定保留期及删除作业，本版仅合成开发环境使用。

Core 只读接口不写入审核决定。`AGREE` 等仅表示 Copilot 建议反馈，不触发 Core 的业务审核状态变化。反馈真实性取决于 Core 身份服务和既有分析归属；没有把个人审核决定作为模型金标准。
