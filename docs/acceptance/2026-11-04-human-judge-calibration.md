# 真人标签与 Judge 抽样校准

2026-11-04，本项目用户在当前对话中逐例提交10个实际标签，并说明审核人为本人。已原样保存为[真人标签](../evaluations/phase4-human-labels-20261104-v1.jsonl)，[来源记录](../evaluations/phase4-human-label-provenance-20261104.json)标明角色、样本及标签指纹。AI没有代填或修正标签；没有认证金融领域专业资质，也没有独立审计盲审过程。

## 实际结果

[校准报告](../evaluations/phase4-human-calibration-20261104-v1.json)：matched10/total10，disagreed0、abstained0、missing0。样本构成为CONFIRMED2、IGNORED2、INSUFFICIENT_EVIDENCE6；100%仅表示这10例项目合成政策上的人机一致率，不是生产准确率或金融专家审定。

当前选定样本SHA256为`a2d1e749ffef619767153799cb0c8a90796e11b0555608754ad813c0348c5742`，与历史Judge清单绑定的`a87e2a12...`不符；工具正确拒绝复用。未修改历史样本、Judge输出或清单来绕过校验。重新冻结[本次输入](../evaluations/phase4-human-calibration-input-20261104-v1.jsonl)，按[本次批次清单](../evaluations/human-judge-batch-20261104-manifest.json)运行新的10次实际Judge请求，再用现有校准CLI比较真人标签与独立收集的输出。

新批次最多10次、预算0.10CNY、不自动重试。冻结请求最大wire1048字节，公开文本协议的wire+1024上界小于4096，输出上限64；保守预留整批0.08704CNY。实际[Judge输出](../evaluations/phase4-human-judge-20261104-v1.jsonl)与[用量](../evaluations/phase4-human-judge-usage-20261104-v1.jsonl)完整保留：实际10次，费用估算0.00584200CNY，未知费用持有0；按官方高峰缓存未命中输入2/输出8CNY每百万token估算，非供应商账单。一次性批次锁保留，无追加或重试。

## 验证与范围

现有Judge解析/预算/校准测试13项通过；评测工具全套187项通过。校准命令退出码0，数据与标签的ID集合严格一致，所有缺失/分歧均纳入分母。通用CLI的`label_verification`和`judge_output_verification`字段仍为`unverified`，没有修改为专家认证；实际对话来源和调用记录在独立来源文件中说明。

本项关闭TASKS中的“取得真实人工标签并完成Judge抽样校准”这一固定合成样本任务。真实RAG、主模型业务质量、持续真实审核反馈及生产质量仍需各自证据。未提交、推送或部署。
