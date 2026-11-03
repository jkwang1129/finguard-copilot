# P4-5 Review Console Implementation Plan

> **For agentic workers:** Use superpowers:executing-plans, test-driven-development and verification-before-completion. 不使用Astra；默认由当前执行者实施，仅最后做一次独立复核。

**Goal:** 持久状态事件、可恢复的授权SSE和可实际使用的审核页面。

**Architecture:** MySQL事件为真相，Redis可选唤醒；有界SseEmitter；独立精确值页面投影；React内存会话及fetch流客户端。

**Tech Stack:** Java17/Boot3.5.8/JDBC/Flyway/Redis，React19/TypeScript/Vite/Vitest。

**Spec:** `docs/superpowers/specs/2026-11-02-p4-5-review-console-design.md`

## Global Constraints

- 当前隔离Phase4工作树；保留P4-4改动，不自动提交/推送。
- V12新增事件结构，不改写V1–V11；事件与状态同事务，Redis失效不丢持久进度。
- 不新增付费模型请求，不开放自由审核输入或工具执行。
- JWT不入URL/存储/日志；页面只消费已授权且受控的投影，金额不转JS浮点。
- SSE进程16/审核员4，60秒，批64，轮询1秒，心跳15秒。

## Review Focus

- 状态CAS失败、回滚和重复投递不产生虚假事件；已有记录只回填快照。
- Redis通知丢失和提交/订阅竞态通过MySQL补读解决。
- 已发送SSE头之后的撤权/异常只能发送受控错误并关闭；不能泄露正文或持有无界线程。
- 精确金额、64位ID、未知费用和已占用但未发出的调用不得被页面改写成错误结论。
- React旧请求/退出后的异步结果不能覆盖新会话；SSE分片和重连不能重复POST。

### Task 1: 持久事件

**Files:** V12、events/AnalysisEventRepository、AnalysisWakeups、JdbcAnalysisRunRepository、OutboxRepository、迁移与事件集成测试。

**Interfaces:** append(id,status,error,time,type)、after(id,cursor,limit)、head(id)；事件sequence用字符串序列化。

- [x] 写首次事件、CAS未命中、回滚、并发序号测试并确认RED。
- [x] 在运行创建/状态变更事务内追加事件；旧记录回填SNAPSHOT；提交后有界唤醒。
- [x] 定向事件/迁移/Outbox测试PASS。

### Task 2: 授权SSE和Redis唤醒

**Files:** events/AnalysisEventStream、RedisEventWakeups、api/analysis/AnalysisEventsController、GetAnalysisService授权入口、相关测试。

**Interfaces:** Task1持久读取；open(id,token,lastId)返回SseEmitter，header游标严格验证。

- [x] 先测权限、未来游标、分批续传、终态、容量释放及Redis不可用，确认RED。
- [x] 实现有界连接/线程/TTL和重新授权；Redis只唤醒，DB周期补读。
- [x] 定向测试PASS，并用实际HTTP验证断线续传。

### Task 3: 精确页面投影与React审核台

**Files:** AnalysisPageService/Controller、frontend/src/api.ts、events.ts、App.tsx/CSS、Vite代理、前后端测试。

**Interfaces:** GET /{id}/view返回精确字符串及已校验结果；已有POST分析/PUT反馈；Core登录开发代理。

- [x] 先测精确数值与受控投影、分片SSE、幂等创建重试、退出和错误状态，确认RED。
- [x] 实现页面和fetch流，纯文本渲染，内存token，状态/证据/引用/费用/反馈/Core入口。
- [x] Java投影测试及前端lint/Vitest/build PASS。

### Task 4: 运行和浏览器验收

**Files:** 验收脚本/报告、运行说明、TASKS/架构/计划。

- [x] 实际Core/Copilot/MySQL/RabbitMQ与替身模型链路、Redis中断补读和重连对照。
- [x] 浏览器执行登录、创建、结果、反馈；保留脱敏截图和后端记录，不保存token/密码。
- [x] 一次独立复核、修复重要问题；Java/Python/前端验证，冻结本轮证据并更新状态。

已有P4-4冻结清单保留为历史，不为了匹配新文件而覆盖；本轮单独冻结。
