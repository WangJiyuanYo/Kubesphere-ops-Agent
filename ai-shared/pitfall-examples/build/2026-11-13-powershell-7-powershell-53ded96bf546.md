# PIT-53ded96bf546: 中文接入脚本只在 PowerShell 7 验证导致系统 PowerShell 启动即报语法错误

| 字段 | 值 |
| --- | --- |
| ID | `PIT-53ded96bf546` |
| 日期 | `2026-11-13` |
| 领域 | `build` |
| 标签 | powershell, utf-8-bom, windows, 启动入口, 脚本兼容 |
| 来源 | 用户启动报错与本机双版本真实进程回归 |

## 需求

本机接入脚本可以直接在用户默认 Windows 终端启动，并保留凭据不回显、受控鉴权和加密保存。

## 现象

用户运行新脚本即报错；Windows PowerShell 5.1 复现 Unexpected token，中文字符串出现乱码，尚未进入身份输入。

## 错误做法

```text
中文 PS1 保存为 UTF-8 无 BOM，并依赖 IsWindows 及仅新运行时提供的 HttpContent.ReadAsStream；验证仅覆盖 pwsh。
```

## 根因

系统 PowerShell 5.1 对无 BOM 文件按旧代码页解析，中文字节可能被解析为错误的语法字符；该宿主也没有 IsWindows 变量及相同的 HTTP API。只有新版本测试无法证明系统默认宿主可启动。

## 修复方案

```text
PS1 使用 UTF-8 BOM；使用兼容的 OSVersion 平台检测及 ReadAsStreamAsync，明确导入当前宿主的安全模块；提供 CMD 入口优先 pwsh、缺失时使用系统 powershell，执行策略仅对本次子进程生效。
```

## 预防规则

交付前通过真实的 Windows PowerShell 5.1、PowerShell 7 和入口的两条分支运行正常、失败及重复执行场景，校验中文编码、返回码、原凭据保留和不泄露；不能只做新版本语法检查。

## 验证

用户启动错误已在系统 5.1 复现。修复后两种宿主与 CMD 的优先7、回退5.1四条路径各8个场景全部通过，共32项，含正常、重复执行、401、302、超限、格式错误、非法地址拒绝和加密回读；重定向目标请求为0。详见本项目 tmp/fix-review-20261113 下 local-platform-script-tests 的四份宿主结果。
