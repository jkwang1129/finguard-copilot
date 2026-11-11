# 运行与验证手册

## 隔离演示

按 README 的步骤克隆两个仓库并运行集成脚本。默认使用 React 端口 `15173` 与 Core 端口 `18080`，仅绑定回环地址。Copilot API、管理端点和中间件留在容器网络内。知识库和审核任务使用合成数据，推理使用本地 HTTP 模型替身。 [快速开始](../README.md#快速开始)

1. 运行成功后，从终端提示的 `target/integration/<项目名>/.env` 读取 `FG_REVIEWER_PASSWORD`，以 `e2e-reviewer` 登录审核台。
2. 打开同目录的 `report.json`，将 `analysisId` 填入「恢复已有分析」。结果页展示状态、证据、引用、费用和反馈选项。可用左侧的审核任务编号发起另一份分析。
3. 通过页面中的 Core 入口查看业务审核台。Copilot 的反馈只保存对建议的评价，业务审核在 Core 单独提交。
4. 演示结束后，执行终端打印的 Compose 清理命令。去掉启动参数 `--keep` 可仅运行自动检查，结束后自动移除隔离资源。

首次构建需要访问容器镜像仓库、Maven Central 和 npm。端口占用时使用 `--web-port` 更换审核台端口。修改 `--core-port` 时，页面内的 Core 跳转地址仍取前端镜像的构建配置，可直接访问实际 Core 端口的 `/demo/index.html`。

## 工程测试

全量验证需要 JDK 17、Docker、Node.js 24 和 uv。Java 集成测试使用 Testcontainers，Python 评测环境使用 Python 3.12。常规测试无需付费模型调用。

```powershell
# Windows
.\scripts\verify.ps1
```

```sh
# Linux / macOS
bash scripts/verify.sh
```

可分别运行 `mvnw`、[前端测试](../frontend/README.md)和[离线评测](../tools/agent-evaluation/README.md)。OpenAPI 在服务的 `/v3/api-docs`，隔离 Compose 的 Copilot 端口只在内部网络可访问。

## 故障排查

| 现象 | 检查方式 |
| --- | --- |
| 构建或启动失败 | 检查 Docker 是否处于 Linux 容器模式，查看终端输出和本次目录的 `failure.log` |
| 登录失败 | 使用当前 Compose 批次的凭据。每次启动会生成新密码 |
| 页面打不开 | 检查本地监听端口和终端打印的地址 |
| 想复验消息与数据恢复 | 使用 [部署与恢复手册](DEPLOYMENT.md) 中的 `--recovery` 或 `--faults` |

模型密钥和临时密码通过本机环境文件或进程变量传递。提交问题时可附上脱敏后的检查结果。
