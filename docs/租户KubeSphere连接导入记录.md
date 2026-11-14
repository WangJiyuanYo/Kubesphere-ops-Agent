# 租户项目 KubeSphere 连接导入记录

| 项目 | 内容 |
| --- | --- |
| 执行日期 | 2026-11-12 |
| 来源项目 | lh-tenant |
| 来源数据库 | PostgreSQL / lh_tenant |
| 来源表 | kubesphere_connection_config |
| 目标 | 当前运行 Agent 的 SQLite `data/agent.db`，agent_console_connection |
| 导入范围 | 两条 enabled=true 的 SERVICE_ACCOUNT 连接 |
| 源数据操作 | 只读事务，无数据库更新、无集群写操作 |

## 导入结果

| 来源编码 | Agent 名称 | API 地址 | 初始命名空间 | 验证 |
| --- | --- | --- | --- | --- |
| ks-nanjing-test | 南京01 [ks-nanjing-test] | https://192.168.3.102:6443 | platform-system | 真实 Pod 只读查询成功，最多展示 10 个 |
| ks-nanjing-02 | 上海集群 [ks-nanjing-02] | https://192.168.3.1:6443 | platform-system | 真实 Pod 只读查询成功，最多展示 10 个 |

Agent 连接 ID 分别为 `80b0f596-3dab-4198-9267-2841b24516ae`、`d638c468-647f-428e-b517-521c94edff0e`。初始命名空间来自租户项目既有连接检查范围，可在 Agent 中编辑。源表未提供单独控制台页面地址，两条记录的 base_url 与 kubernetes_api_url 相同，因此控制台链接留空，不把原生 Kubernetes API 地址当作控制台页面。

两条停用的 PASSWORD 连接 `cluster-01`、`ks-nanjing-dev` 未导入或启用。初次导入时保留原有本机演示连接，共 3 条；随后用户移除了演示连接，当前为 2 条真实连接。此次为一次性复制，源配置后续变化不会自动同步；导入复查按名称中的来源编码后缀识别，不因显示名修正而重复新建。

## 上海集群名称核对（2026-11-12）

`kubesphere_connection_config` 中 `ks-nanjing-02` 仍保留旧名称“南京02集群”，其唯一启用的受管集群记录 `kubesphere_managed_cluster` 名称为“上海集群”，外部名称为 `ShangHai-02`、地域为 `cn-shanghai-2`。初次导入沿用了连接表旧名称，因此页面看起来缺少上海集群。

使用 Agent 既有凭据、原 API 地址以及完整 TLS 校验，只读查询 kube-system UID 和节点，确认集群 UID 为 `abf454d8-9d12-47b4-97cf-cc84a9aa1ec6`，节点为 ks01（`192.168.3.0`）、ks02（`192.168.3.2`）、ks03（`192.168.3.1`）。用户所指 `.0` 与当前 API 入口 `.1:6443` 属于同一集群。

经认证配置 API 将原连接名称修正为“上海集群 [ks-nanjing-02]”，连接 ID 不变；SQLite 比较确认 Token / CA 密文、API、控制台地址和命名空间均不变。修改前备份为 `data/backups/shanghai-name-before-20261112.db`。租户源库没有写入，也未创建重复连接。一次性导入工具现优先取唯一启用受管集群的名称，无唯一匹配时回退连接名称；既有记录按来源编码后缀识别，不自动覆盖用户自定义名称。

刷新页面并重新登录后，“KubeSphere 连接”“部署任务”及对话连接选项均显示上海集群；页面测试连接成功，返回 platform-system 的 10 个 Pod。保留用户已启用的两条部署发现来源。核验期间上海来源曾显示部署读取失败，这与连接名称无关；本轮 Pod 读取成功不代表四类 CR 的发现权限和同步已全部验收。

## 当前监控范围修正（2026-11-12）

用户反馈 `zskjyxgs.lanhaoyun.com` 开通中但 Agent 无记录。租户源库只读确认开通任务 `6328134` 处于 RUNNING / WAITING_FOR_OPERATOR / RUNTIME，站点 `6328132` 对应项目 `prj-bfc29aae80ad454ea71039713bb8fac5`，上海集群的实际共用命名空间为 `t-91440101ma59tq5r3q-zskjyxgs-common`。原 `platform-system` 是此前连接测试范围，并非这个站点的资源命名空间。

在原 API、Token 和 CA 下只读查询目标命名空间，四类 CR list 均返回 HTTP 200，发现 `zskjyxgs-postgres`（Ready）和 `zskjyxgs-runtime`（Progressing / COMPONENTS_PENDING），两者 observedGeneration 均为当前 generation=1；PlatformRelease / ServicePublish 当前为空。

将上海原连接的 namespace 改为上述 common 命名空间，保留名称、地址、密文凭据、连接 ID 和原发现开关；南京连接未改。修改前备份 `data/backups/zskjyxgs-scope-before-20261112.db`。同步成功后两条资源持久化为 PENDING / PRESENT，页面显示“待接管”与“交给 Agent”。后台后续轮询成功且总数仍为 2。LLM 配置、恢复任务和命令执行审计前后未变，未替用户接管排查，也未写入集群或租户源库。

当前上海连接仅监控该站点 common；它不覆盖其他站点或隔离 prod 命名空间。当前产品仍是一条连接一个显式 namespace，不能把本次修正描述成全集群或租户站点自动发现。后续需要多站点 / 多环境自动发现时，应单独实现显式范围配置与来源映射。

页面证据：[站点部署发现](screenshots/zskjyxgs-discovery.png)。

## 凭据、证书和备份

源 Token 和 CA 使用租户项目原主密钥在导入进程内解密，通过回环地址的认证配置 API 保存；Agent 使用自己的主密钥及连接绑定上下文重新 AES-GCM 加密。未将源密文直接当作目标密文使用，也未持久化或打印明文 Token / CA。

已从目标 SQLite 读取密文，在内存中解密并与源值比较一致。配置 API、页面 Token 输入和 CA 输入均不回显内容，只显示已配置状态。每个连接独立信任所配置 CA，保持证书链和主机名校验，不关闭 TLS 验证，不改变系统或其他连接的信任。

导入前使用 SQLite backup API 保存 `data/backups/tenant-import-before.db`；原主密钥保留在原位置。数据目录与备份均被 Git 忽略，恢复时仍需配套原主密钥。临时工具及脱敏结果位于被忽略的 `tmp/`，不构成产品级同步或数据库迁移接口。

## 验收与边界

- 配置 API 与 SQLite 均确认两条记录存在，Token / CA 已加密且可正确解密。
- 重复执行导入检查未重复创建连接；两条真实集群只读检查均成功。
- 页面显示名称、API、platform-system、自定义 CA 和 TLS 校验状态；两个“测试连接”按钮返回成功。
- 编辑窗口中 Token / CA 均为空，提示留空保留，未暴露凭据内容。
- [页面截图](screenshots/tenant-kubesphere-import.png) 展示两条真实连接的检测结果。

当前恢复工作流仍为 DEMO。页面真实连接用于连接测试、对话按需读取快照，以及用户已启用的指定命名空间部署发现，不切换恢复工作流集群，不开启真实 kubectl 或部署恢复。Pod 测试仅证明当时目标命名空间的 Pod 读取可用；本轮另行验证上海站点 common 的四类 CR list 和两条真实发现记录，不代表整个集群健康、其他命名空间权限或平台恢复契约已验收。
