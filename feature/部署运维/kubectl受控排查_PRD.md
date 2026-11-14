# kubectl 受控排查产品需求文档（PRD）

| 项目 | 内容 |
| --- | --- |
| 创建日期 | 2026-11-12 |
| 最后更新 | 2026-11-13 |
| 文档状态 | 实现版，本机模拟与契约验证，真实 kubectl / 集群待联调 |
| 关联模块 | 诊断 Graph / 命令审计 / 部署任务详情 |

## 产品目标与范围

按用户要求增加“生成命令 → 指令安全分析 → 执行 → 分析结果”链路，使 Agent 在诊断中补充有范围限制的只读 Kubernetes 证据。命令执行默认关闭；DEMO 模式只产生模拟结果，真实 Kubernetes 模式才启动配置的 kubectl 可执行文件。

不提供任意 Shell、自由 kubectl 文本或写入命令。对话另提供基于已选择 KubeSphere 连接的受控只读 API 采证 Graph（见《对话只读排查 PRD》），无需 CLI，也不会切换本工作流的独立执行身份。现有批准、恢复预算、平台原子恢复和结果验证规则继续生效。

## 角色与前置条件

部署管理员配置命令开关、可执行文件绝对路径、专用 kubeconfig 绝对路径及明确 context。普通运维可通过任务详情或认证 API 查看计划和审计，模型只提出结构化建议。所有 OPERATOR 仍共享工作空间。

启用真实模式后，Fabric8 和 kubectl 使用同一冻结身份；context 必须存在，集群须为 HTTPS 且校验证书，禁止 kubeconfig 中 exec / auth-provider 插件。专用身份应使用现有命名空间级只读 RBAC，不访问 Secret。不得因命令失败自动提升权限。模型及 KubeSphere 页面配置不能切换命令身份。

## 当前流程与规则

Graph 为 `sanitize → classify → recallMemory → generateCommands → assessCommands → executeCommands → analyzeResults → diagnose`。

1. 脱敏、规则分类、记忆召回遵循原规则；初始动作只依据本次可信采证分类。
2. 服务端先构建本任务资源目录：绑定 CR、沿 ownerReference UID 关联的 Deployment / StatefulSet / Job / ReplicaSet 下的 Pod。每目标最多 4 个 Pod，每 Pod 最多 4 个普通或初始化容器；无 CR 所有权链的 Helm 资源不进入目录。
3. 模型生成 `operation、subjectId、purpose`。operation 仅允许 GET、DESCRIBE、EVENTS、LOGS；subjectId 必须来自目录，purpose 仅为说明。模型不可用或结构不完整时使用固定只读模板。空命令计划可直接进入最终诊断。
4. 独立 Java 安全分析检查操作白名单、目录身份、命名空间、资源标识和命令数量。日志必须绑定 Pod 和明确容器；事件按目录资源 UID 过滤。禁止 Secret、任意参数、插件、watch、集群切换和所有写入操作。安全不依赖模型自评，也不依赖 Shell 字符过滤。
5. 每个故障证据快照最多 3 条命令，超出数量则整批拒绝，最多保存预算内的拒绝记录。确定性批次身份由任务 ID 和采证时间生成。检查结果、固定参数与执行意图先持久化，再逐条认领执行。
6. 每条执行前复核活动 DIAGNOSING 任务租约、冻结配置和当前目录；Java 组装 argv，由 ProcessBuilder 直接调用固定程序，无 Shell。执行前后均复核资源 UID，身份变化时不采纳输出。读取与身份核验无法构成 Kubernetes 原子事务，因此结果仅代表该时间窗口的观测，不能作为独立恢复授权。
7. 进程最长 5 秒，kubectl 请求超时参数固定 4 秒；输出合并最多 8192 字节，脱敏后保留最多 4000 字符；日志固定 tail=60、limit-bytes=4096，不接受用户额外选项。命令阶段在请求边界检查 45 秒预算；启用真实命令后 Fabric8 单请求读取超时为 2 秒。超时、中断、超限杀掉子进程及已发现后代进程，关闭 stdin，不继承模型或平台凭据环境变量。
8. 结果分析展示状态与安全结论；任何拒绝、失败、超时、不完整或状态不明均否决自动恢复。成功输出中的认证、OOM、容量、存储等风险词也可否决。新结果只能降低原权限，不能把人工动作提升为重试。
9. 最终诊断结合脱敏证据、历史记忆和命令结果，区分事实、建议和失败信息，附真实命令审计 ID。模型没有直接执行工具，不能声称完成未执行的动作。

## 状态、持久化与重放

命令状态为 APPROVED / REJECTED → RUNNING → SUCCEEDED / FAILED / TIMED_OUT / OUTPUT_LIMIT。状态更新必须属于当前任务租约，过期执行者不能落盘成功结果。

已保存批次再次进入 Graph 时读取原审计，不重新调用模型或执行命令。原 APPROVED / RUNNING 记录视为中断或状态不明，诊断标记 UNKNOWN 并转人工；数据库保留原审计状态供核查，不伪造已完成。新故障快照可生成新批次，但不能重置部署恢复预算。

审计表由 PostgreSQL V7 / SQLite V2 迁移建立，保存任务、批次、序号、结构化计划、安全结论、固定 argv、状态、时间及脱敏输出。命令审计不自动清理，保留来源引用。即使最终诊断未保存，已执行命令审计仍可查询。

跨 Graph 节点使用明确 JSON 编解码；每次调用显式覆盖范围，未绑定任务的诊断不得沿用上次范围。当前每实例 Graph 调用串行隔离，等待锁可中断，其他工作槽位及租约保护保持有效。

## 权限、页面与接口

`GET /api/runs/{id}/commands` 需要 OPERATOR，最多返回 100 条记录。部署任务详情同步查询并展示命令审计 JSON；聊天可另行查看自身轮次的只读 API 采证记录，不复用此处 Run / CLI 身份。没有执行任意命令的 HTTP 接口。

`BUSINESS_DICTIONARY_OR_CONFIG`：操作白名单和执行状态属于安全策略代码枚举；命令开关、路径、context、超时和数量上限属于受校验的服务端配置，数量 1–3、单条超时 1–5 秒。模型只能生成计划，不维护策略。Pod 与日志采样上限是代码采证预算，需修改代码并回归后才能调整。

安全依据：采用 [Kubernetes RBAC 最佳实践](https://kubernetes.io/docs/concepts/security/rbac-good-practices/) 的最小权限原则，以及 [OWASP OS Command Injection Defense Cheat Sheet](https://cheatsheetseries.owasp.org/cheatsheets/OS_Command_Injection_Defense_Cheat_Sheet.html) 的参数化与允许列表思路。此处不宣称完成组织级安全认证。

## 异常与恢复

目录读取失败不授予命令权限；命令校验拒绝保留理由并转人工。二进制缺失或配置无效时启用真实模式启动失败，不静默切换默认集群。已运行服务的 kubeconfig 内容发生变化后，后续命令失败关闭，需管理员核对并重启。

命令失败不无限重试，不自动写资源或扩大 RBAC。进程退出、超时和租约丢失留存审计，状态不明由运维检查；模型生成失败可以回退模板，但安全拒绝的计划不能通过回退模板绕过拒绝。

## 验收标准

1. Graph 实际按八节点执行；未启用时保持原诊断功能。
2. 模型返回结构化计划可解析；危险操作、未知 ID、跨命名空间、Secrets、标识注入与超预算被拒绝。
3. CR → Deployment → ReplicaSet → Pod 所有权链内资源可入目录，无关 Pod、替换 UID、权限失败不授权。
4. 先落审计再启动进程；输出脱敏，失败、超时、输出超限会否决自动恢复。
5. 真实受控子进程验证参数字面传递、退出码、输出上限、超时和中断终止；不执行用户提供的 Shell。
6. PostgreSQL / SQLite 审计可保存和读取，过期租约不能回写；原批次重入不重复执行，状态不明转人工。
7. 并发任务及无范围诊断不能继承别的任务身份；保留记忆引用，历史内容不能授予执行权限。
8. 任务详情可查看命令计划、判定和结果；匿名审计 API 拒绝。

## 部署与现场边界

需部署方安装官方 kubectl 并匹配集群版本，当前镜像不内置。真实集群、只读账号和证书、多集群 context、实际 CLI 输出及备份恢复仍待现场联调。本机 Java 子进程验证不等于真实 kubectl 验收；DEMO 结果始终明确标注模拟。
