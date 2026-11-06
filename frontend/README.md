# FinGuard Copilot 审核页面

React / TypeScript / Vite 审核台：Core 账号登录、分析创建、SSE 状态恢复、精确金额证据、费用与版本、不可覆盖反馈和 Core 决策入口。

完整演示使用 [隔离运行手册](../docs/RUNBOOK.md)。单独开发前端时，在本目录运行 `npm ci` 和 `npm run dev`。Vite 代理要求本机 Core 与 Copilot 服务已启动，实际地址见 [代理配置](vite.config.ts)。

```powershell
npm ci
npm run dev
npm run test -- --run
npm run lint
npm run build
```
