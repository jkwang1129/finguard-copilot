# ADR-0008：Outbox、RabbitMQ 与 Checkpoint 保证 Agent 可靠执行

- 状态：Accepted
- 日期：2026-08-20

## 背景

分析 API 返回 `202` 后，进程可能崩溃、消息可能重复，Agent 也可能因 HITL 暂停数小时。
仅使用内存线程池无法保证任务不丢失，也不能可靠恢复 Graph 状态。

## 决策

创建分析时在一个 MySQL 事务中写入运行、事实快照和 Outbox 事件；Relay 至少一次发布到
RabbitMQ；Worker 以分析、阶段和尝试号幂等消费。Graph 节点、运行级 Working Memory、
工具摘要、剩余预算和等待原因写入持久化 Checkpoint。HITL 恢复使用新的受权请求并复查
权限、状态和过期时间。

## 后果

运行可恢复、回放并接受故障演练，消息重投不会重复执行工具。代价是必须实现 Outbox
清理、死信处理、幂等键、Checkpoint 兼容性和 RabbitMQ 运维。
