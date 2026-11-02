# 方向性建议的审定政策配置

`CONFIRMED` / `IGNORED` 需要服务端可确定核验的授权。模型只提供候选建议，不能注册规则、修改条件或指定可信版本。`INSUFFICIENT_EVIDENCE` 保留为保守路径。

三种授权来源互斥：

1. `LARGE_AMOUNT` 使用既有 Core 风险事实核验和精确绑定的项目大额复核政策，不能被通用规则覆盖。
2. `numeric-guard.rules` 核验已审定的单阈值规则；方向建议只能使用那一份精确绑定的政策证据。
3. `reviewed-policy.sets` 核验完整政策包的 ALL 条件。没有匹配授权来源的方向建议以 `FACT_VALIDATION_FAILED` 拒绝。

每个政策包声明版本、规则和币种；其全部成员都必须由检索保留且被候选精确引用。文档 ID、版本、章节、元数据 SHA-256 与实际正文 SHA-256 必须一致。漏成员、额外文档、重复成员、内容变化都不能匹配。多个政策包匹配或多个成员给出不同建议时拒绝，不默认采用第一份文档。相同规则同时配置单阈值与政策包时启动失败。

条件来自可信配置，不解析知识文档或模型正文来创建条件：`AMOUNT` 支持 EQ/GT/GTE/LT/LTE，使用精确十进制；`CURRENCY` 和 `ACCOUNT_STATUS` 只支持 EQ。包内每份政策的条件为 AND，命中与未命中分别声明建议。政策包没有隐含优先级。必须审定互相冲突政策的适用性或继续拒绝。

以下只是配置结构示例；文档及 SHA-256 必须换成经项目业务审定并实际发布的版本，不能直接用于真实业务。默认政策包列表为空，测试用 `P4_COMPLEX` 不会自动启用。

```yaml
finguard:
  copilot:
    reviewed-policy:
      sets:
        - version: project-review-v1
          rule-code: PROJECT_COMBINED_RULE
          currency: USD
          policies:
            - binding:
                document-id: approved-project-policy
                document-version: v1
                section-path: Document
                content-sha256: '<实际发布正文的64位小写SHA-256>'
              conditions:
                - field: AMOUNT
                  operator: GTE
                  value: '100.00'
                - field: ACCOUNT_STATUS
                  operator: EQ
                  value: FROZEN
              on-match: CONFIRMED
              on-miss: IGNORED
```

严格证据绑定可能增加拒答：检索中混入未审定的说明文档也会阻止方向建议。应调整已审定索引/检索范围或政策包，不得移除校验来让模型输出通过。已知注入模式检测继续作为附加检查；核心授权不依赖攻击文本是否被正则识别。

Worker 恢复存量未终态结果时也重新核验方向授权，不再调用模型；无法核验的方向结果转为失败并隐藏最终结果。无政策引用的保守拒答在 schema/事实校验后直接恢复，保持知识服务故障下的可用性。已经终态的历史运行不被自动重写，历史结果不因本次修复获得新的质量保证。

V8 将快照与结果 JSON 列改为 LONGTEXT，并保留 JSON_VALID、外键与生命周期约束，写入时不再 CAST 为 MySQL 二进制 JSON。规范化快照哈希与模型 v1 数字契约不变；解析器、结果校验及 GET 读取用 BigDecimal。旧的正确快照仍可验证；之前已经被舍入的值无法还原，继续拒绝哈希失配，不能改哈希来接受损坏内容。V1–V7 未修改。本次只在测试容器迁移，未操作生产数据库。
