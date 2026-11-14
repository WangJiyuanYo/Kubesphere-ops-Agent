# PIT-dfde69b5e20a: 恢复授权必须绑定动作冻结基线而非最近观察结果

| 字段 | 值 |
| --- | --- |
| ID | `PIT-dfde69b5e20a` |
| 日期 | `2026-11-11` |
| 领域 | `workflow` |
| 标签 | authorization, generation, snapshot |
| 来源 | src/main/java/com/lanhao/ops/port/KubernetesDeploymentPort.java 与 RunEngine.java |

## 需求

同一恢复请求只能解释自身造成的一次发布重试序号及资源代次递增，不得吸收后续其他发布。

## 现象

连续观察到 generation 和 retrySequence 各增一时，同一 recovery 被反复用作授权，第二次代次变化仍被接受。

## 错误做法

```text
actualGeneration == effectiveGeneration + 1 且 sequence == observedSequence + 1；接受后覆盖 effectiveGeneration 和 observedSequence。
```

## 根因

把不断移动的观察位置当成固定授权边界，使一次动作的有限权限随着每次采样移动。

## 修复方案

```text
在恢复意图落盘时冻结 actionGenerations 和 actionRetrySequences。观察更新仍保存实际代次，但放行必须同时满足相对动作冻结基线的一次递增、恢复身份及配置摘要一致。
```

## 预防规则

授权前提与进度游标分别保存；有界动作始终按原始冻结快照核验，不能用前一次观察结果重新授予同一动作权限。

## 验证

KubernetesDeploymentPortTest.sameRecoveryCannotAuthorizeASecondGenerationIncrement 修复前未抛出预期异常，修复后拒绝第二次递增；完整 43 项测试通过。
