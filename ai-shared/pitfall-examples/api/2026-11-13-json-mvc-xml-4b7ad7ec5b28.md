# PIT-4b7ad7ec5b28: 未声明 JSON 的 MVC 接口在跨服务调用中返回 XML

| 字段 | 值 |
| --- | --- |
| ID | `PIT-4b7ad7ec5b28` |
| 日期 | `2026-11-13` |
| 领域 | `api` |
| 标签 | json, 内容协商, 跨服务联调 |
| 来源 | 独立部署运维 Agent 与租户平台的生产者消费者契约回归 |

## 需求

Agent 平台契约稳定返回 JSON，不依赖客户端或 MVC 默认转换器顺序。

## 现象

实际 Controller 无 Accept 请求返回 application/xml，消费者按 JSON 解析失败；手工 JSON mock 的测试仍通过。

## 错误做法

```text
@RequestMapping(BASE)；RestClient 请求没有明确 Accept。
```

## 根因

服务端转换器按可用协议选择 XML，消费者单方面假定响应是 JSON，单侧测试没有执行生产者序列化。

## 修复方案

```text
Controller 声明 produces=application/json，Agent 平台目录和恢复客户端明确 Accept: application/json。
```

## 预防规则

对真实 Controller 检查 Content-Type 与完整 JSON envelope，消费者用生产者导出的响应，不用臆造响应替代传输契约。

## 验证

修复前 MockMvc 实测响应为 XML 并导致 JSONPath 断言失败；修复后 DeploymentAgentControllerTest 在无 Accept 请求下验证 JSON、字符串 ID、认证身份透传通过；消费者相关 22 项专项通过。
