# ADR-0011：SSE 只传受控事件并配套轻量演示页面

- 状态：Accepted
- 日期：2026-08-20

## 背景

Swagger 足以验收 API，但难以在面试中直观展示 Agent 节点、工具、引用和降级。直接流式
转发模型 Token 会把未经 Schema、事实和引用校验的内容暴露给用户。

## 决策

使用 React、TypeScript 和 Vite 构建轻量审核页面。SSE 只发送阶段、状态、受控错误码和
最终结果可用事件；最终正文仍从查询 API 获取。断线通过 Last-Event-ID 或查询 API 恢复。
页面只在内存保存 Core JWT，并通过支持 `Authorization` 请求头的 Fetch Streaming 消费
SSE，不把 Token 写入 URL 或 Local Storage。浏览器不得收到完整 Prompt、事实快照、
推理过程、工具原文或未经校验的模型 Token。SSE 以 MySQL 中单调递增的受控事件序列作为
真相源，Redis Pub/Sub 只负责唤醒；重连使用 Last-Event-ID 补发缺失事件。

## 后果

面试可以在数分钟内展示执行轨迹、证据、成本和安全边界，同时保持输出校验。代价是增加
一个小型前端构建和 SSE 权限、重连测试，但不扩展为通用前端平台。
