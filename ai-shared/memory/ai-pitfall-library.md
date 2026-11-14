# 工程踩坑索引

仅记录本独立项目已确认、已修复且通过验证的可复用缺陷。

<!-- pitfall-index:start -->
| ID | 日期 | 领域 | 摘要 | 预防规则 | 案例 |
| --- | --- | --- | --- | --- | --- |
| PIT-5686983c6179 | 2026-11-10 | workflow | 恢复响应丢失后须先找回动作身份再核验资源代次 | 所有可重放外部动作须先落盘意图与幂等键；接管时先对账动作身份，再解释目标状态变化，不通过生成新键掩盖不确定结果。 | [2026-11-10-pitfall-5686983c6179.md](../pitfall-examples/workflow/2026-11-10-pitfall-5686983c6179.md) |
| PIT-f1b11c164ade | 2026-11-11 | workflow | 等待批准状态也必须进入到期回收队列 | 所有具有生命周期上限的等待状态都必须有持久化唤醒或到期回收路径；批准、恢复等入口需独立复核时间前提；错误返回不得回滚必须提交的失效状态。 | [2026-11-11-pitfall-f1b11c164ade.md](../pitfall-examples/workflow/2026-11-11-pitfall-f1b11c164ade.md) |
| PIT-dfde69b5e20a | 2026-11-11 | workflow | 恢复授权必须绑定动作冻结基线而非最近观察结果 | 授权前提与进度游标分别保存；有界动作始终按原始冻结快照核验，不能用前一次观察结果重新授予同一动作权限。 | [2026-11-11-pitfall-dfde69b5e20a.md](../pitfall-examples/workflow/2026-11-11-pitfall-dfde69b5e20a.md) |
| PIT-707a90263fe6 | 2026-11-12 | workflow | Graph 跨节点传递与调用复用必须显式隔离任务身份 | Graph 中的任务、资源和授权上下文须每次显式覆盖；复杂数据使用可验证的序列化契约；同一编译图的调用必须隔离，并用连续调用与并发测试验证不会继承其他任务身份。 | [2026-11-12-graph-707a90263fe6.md](../pitfall-examples/workflow/2026-11-12-graph-707a90263fe6.md) |
| PIT-63f5da80fee4 | 2026-11-12 | api | 模型只能读取显式传入的连接配置与本轮证据 | 逐项追踪页面配置、服务端持久化、上下文构建与模型实际输入；分别验收未绑定、仅元信息、成功快照、采集失败和失效身份，不能用模型的自然语言承诺代替采证。 | [2026-11-12-pitfall-63f5da80fee4.md](../pitfall-examples/api/2026-11-12-pitfall-63f5da80fee4.md) |
| PIT-28d971ea75c8 | 2026-11-12 | workflow | 会话引用配置 ID 不能替代目标身份冻结与跨来源归属校验 | 对话、调查、任务、命令和记忆均须携带显式来源身份。编辑目标不能让历史上下文迁移；相同目标的凭据轮换可以继续。无法证明来源时禁止真实执行及自动经验召回。 | [2026-11-12-id-28d971ea75c8.md](../pitfall-examples/workflow/2026-11-12-id-28d971ea75c8.md) |
| PIT-baeeceee19e9 | 2026-11-13 | api | 部署生命周期身份不能作为应用健康版本 | 跨系统标识先定义生命周期语义与真实来源；用两个不同的版本值做生产者和消费者契约回归，禁止复用同一夹具值掩盖差异。 | [2026-11-13-pitfall-baeeceee19e9.md](../pitfall-examples/api/2026-11-13-pitfall-baeeceee19e9.md) |
| PIT-98ed72f09316 | 2026-11-13 | workflow | 意图预算不能充当已执行恢复的可信证明 | 区分计划、意图、受理、完成和因果证明；成功知识必须引用可信动作与验收证据，覆盖自然收敛、无凭据和历史队列重放。 | [2026-11-13-pitfall-98ed72f09316.md](../pitfall-examples/workflow/2026-11-13-pitfall-98ed72f09316.md) |
| PIT-0db364bd0a9b | 2026-11-13 | api | 只读目录详情错误复用恢复快照导致监控被验收配置阻断 | 按业务意图区分只读映射和恢复快照。导出真实生产者响应供消费者解码回归，并覆盖目录正常、缺少恢复配置、关联仍可读取。 | [2026-11-13-pitfall-0db364bd0a9b.md](../pitfall-examples/api/2026-11-13-pitfall-0db364bd0a9b.md) |
| PIT-4b7ad7ec5b28 | 2026-11-13 | api | 未声明 JSON 的 MVC 接口在跨服务调用中返回 XML | 对真实 Controller 检查 Content-Type 与完整 JSON envelope，消费者用生产者导出的响应，不用臆造响应替代传输契约。 | [2026-11-13-json-mvc-xml-4b7ad7ec5b28.md](../pitfall-examples/api/2026-11-13-json-mvc-xml-4b7ad7ec5b28.md) |
| PIT-ce14e1ebc32e | 2026-11-13 | workflow | 完成的 Job Pod 被当作异常占满采证名额导致遗漏真正失败组件 | 覆盖大量成功 Job 与靠后失败 Job 混合场景；名称提示只能在已证明归属范围内影响排序，不能成为授权；保持请求、对象、日志和时间预算。 | [2026-11-13-job-pod-ce14e1ebc32e.md](../pitfall-examples/workflow/2026-11-13-job-pod-ce14e1ebc32e.md) |
| PIT-53ded96bf546 | 2026-11-13 | build | 中文接入脚本只在 PowerShell 7 验证导致系统 PowerShell 启动即报语法错误 | 交付前通过真实的 Windows PowerShell 5.1、PowerShell 7 和入口的两条分支运行正常、失败及重复执行场景，校验中文编码、返回码、原凭据保留和不泄露；不能只做新版本语法检查。 | [2026-11-13-powershell-7-powershell-53ded96bf546.md](../pitfall-examples/build/2026-11-13-powershell-7-powershell-53ded96bf546.md) |
| PIT-STAGE-AUTH-REVISION | 2026-11-13 | workflow | 新阶段事实错误沿用旧序号的授权时间 | 每次事实修订先使授权失效；当前平台 GET 必须证明相同 sequence，不能用时间新鲜或 Token 摘要代替内容一致性。 | [2026-11-13-stage-authorization-revision.md](../pitfall-examples/workflow/2026-11-13-stage-authorization-revision.md) |
<!-- pitfall-index:end -->
