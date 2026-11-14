package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;

/** 平台阶段与 CR 调查的映射账本；发现本身不调用模型或创建恢复动作。 */
@Service
public class PlatformCatalog {
    public record View(boolean enabled,String warning,Instant checkedAt,List<PlatformCatalogGateway.Entry> items,boolean hasMore) {}
    public record Mapping(UUID connectionId,List<UUID> investigations,String message) {}
    private final PlatformCatalogGateway gateway;
    private final DiscoveryStore discovery;
    private final DiscoveryService scanner;
    private final ConsoleSettings settings;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final SqlDialect db;
    public PlatformCatalog(PlatformCatalogGateway gateway,DiscoveryStore discovery,DiscoveryService scanner,ConsoleSettings settings,
            JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json) {
        this.gateway=gateway;this.discovery=discovery;this.scanner=scanner;this.settings=settings;this.jdbc=jdbc;this.tx=tx;this.json=json;db=new SqlDialect(jdbc);
    }
    public View view(int offset) {
        if(offset<0||offset>100000)throw new IllegalArgumentException("目录分页无效");
        if(!gateway.enabled())return new View(false,"平台部署目录尚未启用，请配置受权平台接口。",null,List.of(),false);
        var status=jdbc.query("SELECT warning,checked_at FROM agent_platform_catalog_source WHERE source_key=?",(rs,i)->Map.entry(rs.getString(1),rs.getLong(2)),gateway.source());
        var items=jdbc.query(db.sql("SELECT payload@text@ FROM agent_platform_catalog WHERE source_key=? ORDER BY observed_at DESC,provisioning_id LIMIT 31 OFFSET ?"),
                (rs,i)->{try{return json.readValue(rs.getString(1),PlatformCatalogGateway.Entry.class);}catch(Exception invalid){throw new ConsoleFailure("平台目录记录不可读取");}},gateway.source(),offset);
        return new View(true,status.isEmpty()?"尚未同步平台目录":status.getFirst().getKey(),status.isEmpty()||status.getFirst().getValue()==0?null:Instant.ofEpochMilli(status.getFirst().getValue()),items.stream().limit(30).toList(),items.size()>30);
    }
    public List<PlatformCatalogGateway.Entry> matches(UUID id) {
        if(!gateway.enabled())return List.of();
        var task=discovery.task(id);var connection=discovery.connection(task);
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_platform_catalog WHERE source_key=? AND payload->>'projectId'=? AND payload->>'namespace'=? ORDER BY provisioning_id LIMIT 30"),
                (rs,i)->{try{return json.readValue(rs.getString(1),PlatformCatalogGateway.Entry.class);}catch(Exception invalid){throw new ConsoleFailure("平台目录记录不可读取");}},gateway.source(),task.observation().projectId(),connection.namespace())
                .stream().filter(e->e.clusterApiUrl()!=null&&!e.clusterApiUrl().isBlank()&&DeploymentIdentity.endpoint(connection.apiUrl()).equals(DeploymentIdentity.endpoint(e.clusterApiUrl()))).toList();
    }
    @Scheduled(fixedDelayString="${agent.platform-catalog.poll-interval:30000}")
    public void poll() {if(gateway.enabled())try{sync();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("平台目录同步未完成，异常类型 {}",failure.getClass().getSimpleName());}}
    public View sync() {
        if(!gateway.enabled())return view(0);
        UUID owner=UUID.randomUUID();
        boolean claimed=Boolean.TRUE.equals(tx.execute(status->{
            jdbc.update("INSERT INTO agent_platform_catalog_source(source_key) VALUES(?) ON CONFLICT DO NOTHING",gateway.source());
            return jdbc.update(db.sql("UPDATE agent_platform_catalog_source SET lease_owner=?,lease_until=@afterSeconds@ WHERE source_key=? AND (lease_until IS NULL OR lease_until<=@now@)"),owner,90,gateway.source())==1;
        }));
        if(!claimed)return view(0);
        try {
            var batch=gateway.scan();
            tx.executeWithoutResult(status->{
                if(jdbc.update(db.sql("UPDATE agent_platform_catalog_source SET checked_at=?,warning=?,lease_owner=NULL,lease_until=NULL WHERE source_key=? AND lease_owner=? AND lease_until>@now@"),
                        System.currentTimeMillis(),batch.partial()?"目录超过扫描页数上限；当前只展示已同步部分，可提高服务端扫描页数。":"",gateway.source(),owner)!=1)return;
                // 完整扫描才删除不再有权看到的记录；失败及部分扫描保留记录并明确提示。
                if(!batch.partial())jdbc.update("DELETE FROM agent_platform_catalog WHERE source_key=?",gateway.source());
                for(var item:batch.items())jdbc.update(db.sql("INSERT INTO agent_platform_catalog(source_key,provisioning_id,payload) VALUES(?,?,@json@) ON CONFLICT(source_key,provisioning_id) DO UPDATE SET payload=excluded.payload,observed_at=@now@"),gateway.source(),item.id(),encode(item));
            });
        }catch(RuntimeException failure) {
            tx.executeWithoutResult(status->{
                int changed=jdbc.update("UPDATE agent_platform_catalog_source SET warning=?,lease_owner=NULL,lease_until=NULL WHERE source_key=? AND lease_owner=?",
                        failure instanceof ConsoleFailure?failure.getMessage():"平台目录同步失败，当前展示上次成功结果",gateway.source(),owner);
                if(changed==1&&failure instanceof PlatformCatalogGateway.AuthorizationFailure)jdbc.update("DELETE FROM agent_platform_catalog WHERE source_key=?",gateway.source());
            });
        }
        return view(0);
    }
    public Mapping monitor(String id,UUID connectionId) {
        var current=gateway.current(id);var connection=settings.connection(connectionId);
        if(current.unavailableReason()!=null&&!current.unavailableReason().isBlank()||current.namespace()==null||current.namespace().isBlank()
                ||current.projectId()==null||current.projectId().isBlank()||current.clusterApiUrl()==null||current.clusterApiUrl().isBlank())throw new ConsoleFailure("平台项目尚未具备可关联的集群和命名空间，请稍后同步");
        if(!DeploymentIdentity.endpoint(connection.apiUrl()).equals(DeploymentIdentity.endpoint(current.clusterApiUrl())))throw new ContextMismatch("所选连接与平台项目不属于同一集群");
        var source=discovery.source(connectionId);var namespaces=new ArrayList<>(source.namespaces());
        if(!namespaces.contains(current.namespace()))namespaces.add(current.namespace());
        discovery.configure(connectionId,true,source.revision(),namespaces);
        var result=scanner.scan(connectionId,true);if(result.error()!=null&&!result.error().isBlank())throw new ConsoleFailure(result.error());
        if(settings.connection(connectionId).revision()!=connection.revision())throw new ContextMismatch("连接已变化，请重新关联平台项目");
        var ids=jdbc.query("SELECT id FROM agent_discovered_task WHERE connection_id=? AND origin_hash=? AND presence='PRESENT' AND observation->>'projectId'=? AND observation->'target'->>'namespace'=? ORDER BY observed_at DESC LIMIT 10",
                (rs,i)->SqlDialect.uuid(rs,1),connectionId,DiscoveryGateway.origin(DiscoveryStore.scoped(connection,current.namespace())),current.projectId(),current.namespace());
        return new Mapping(connectionId,ids,ids.isEmpty()?"已加入监控，平台尚未创建可关联的 CR；后续扫描将自动收录。":"已关联平台项目与当前 CR，请选择资源后交给 Agent 调查。");
    }
    private String encode(Object value){try{return json.writeValueAsString(value);}catch(Exception failure){throw new ConsoleFailure("平台目录不可保存");}}
}
