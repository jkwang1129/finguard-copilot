# ADR-0007：Python 只承担评测、实验和跨框架基线

- 状态：Accepted
- 日期：2026-08-20

## 背景

Java/Spring 是本项目的生产运行时，但主流 Agent 和评测生态大量使用 Python。为了增加
Python 而复制一套 FastAPI 生产服务，会产生双实现和契约漂移，却不能提升业务价值。

## 决策

建立 Python 3.12 配套工程，使用 uv、pytest、Pydantic、Pandas 和 LangGraph。它通过
公开 API 或脱敏导出消费与 Java 服务相同的 Tool Schema 和 Golden Dataset，用于指标
计算、Judge、Bad Case、报告复现和跨框架参考基线；不得直连生产数据库或成为线上依赖。

## 后果

项目能够证明 Python、主流 Agent 框架和评测能力，同时保持一套生产业务逻辑。代价是
需要维护跨语言数据 Schema 和独立测试，但不需要维护第二套线上服务。
