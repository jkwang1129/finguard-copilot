# DeepSeek 文本请求输入上界 v1

本项目P4-3.1的调用前控制，适用范围：`https://api.deepseek.com/chat/completions`、`deepseek-flash`、固定system/user两条文本消息、无工具/图像、thinking disabled、JSON object输出。其他模型/端点拒绝，不能把此方法推广为所有供应商的Token计数器。

## 证据与计算

2026-11-02读取供应商官方[deepseek-recipe编码文档](https://github.com/deepseek-ai/deepseek-recipe/blob/935817c66f5d6e64838f419692545f06787e485f/docs/tokenizer.md)和[编码实现](https://github.com/deepseek-ai/deepseek-recipe/blob/935817c66f5d6e64838f419692545f06787e485f/deepseek-recipe-encoding/src/v4/dsv41.rs)，固定提交`935817c66f5d6e64838f419692545f06787e485f`。

[Tokenizer来源](https://github.com/deepseek-ai/deepseek-recipe/blob/935817c66f5d6e64838f419692545f06787e485f/static/tokenizers/v41/README.md)声明SHA256为`81f64d1248a68ce3663e07ab3ee48b851e5df0e32d27cb98e4c9a268151e8d99`。本机下载官方JSON到忽略目录并核对同一哈希；未将8MB文件或其内容嵌入产品。检查得到：BPE、identity normalizer（空normalizers序列）、Split预切分后ByteLevel，`add_prefix_space=false`。

在这一编码下，普通文本先按UTF8字节表示，BPE合并不会增加字节单元数；特殊词匹配也不会展开输入。产品把**最终HTTP请求JSON的UTF8字节长度 + 1024**作为输入Token保守上界：JSON已包含完整system/Schema/user文本及转义，额外1024覆盖该固定两消息、非思考JSON-object协议的固定角色标记和响应格式说明。共享`CompatibleChatRequestPayload`用于测量与HTTP发送，避免只计算裁剪前文本或遗漏Schema。不合法UTF16代理字符拒绝，避免编码替换产生低估。

这通常显著高于实际Token用量，会更早裁剪/拒绝。原`TokenEstimator.conservative()`仅用于上下文分配，不能代替此调用前检查。输入上界不得超过应用与模型的输入配置上限，输出请求上限同样由服务端限制。

## 适用假设和失效

这里证明的是**上述公开编码及协议假设下的保守界限**，不是供应商私有服务实现证明。官方[Token说明](https://api-docs.deepseek.com/quick_start/token_usage/)也明确离线估算与实际用量可能不同。模型别名可被供应商切换；记录公开编码版本不等于锁定远端权重或服务端模板。价格有效期初始为2026-11-02T00:00:00Z至2026-12-02T00:00:00Z（结束不含），过期拒绝；新编码、模板、模型或端点需重新核对并新增版本。

供应商返回用量超过输入上界/输出上限或缺少可信用量时，后续预算治理必须保留差异/UNKNOWN_HELD及核对事件，不能声称实际账单已被应用绝对保证。该核对属于P4-3.2/4，不能用预检查掩盖差异。

## 配置和版本

`finguard.copilot.governance.max-call-cny`默认0.10元，`price-valid-from`/`price-valid-until`控制当前价格生效范围。真实模型要求CNY、输入/输出均为正价，未定价版本拒绝；历史价格不覆盖。配置首次登记后同一价格版本的价格/有效期变动会发生内容冲突，必须使用新版本。

该文件是算法与假设说明；初始Token上界版本`deepseek-v41-text-byte-bound-v1`连同源提交和Tokenizer哈希进入不可变运行清单。无密钥、正文或请求原文入库。

隔离进程验收另有显式fixture-mode，固定loopback端点`http://127.0.0.1:18765`及`local-test-stub`，使用单独`fixture-byte-bound-v1`标记，可为0价。该模式不能指向远端模型；其报告只代表替身协议/工程控制，不是DeepSeek Token计数或真实费用证据。
