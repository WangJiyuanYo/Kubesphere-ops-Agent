package com.lanhao.ops.console;

import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.persistence.SqlDialect;
import jakarta.validation.Validator;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.time.Instant;
import java.util.*;
import static com.lanhao.ops.console.ProvisioningModel.*;

@Service
public class AutonomyPolicies {
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final ProvisioningStore store;
    private final ConsoleSettings settings;
    private final DiscoveryStore discovery;
    private final Validator validator;
    private final ProvisioningProperties options;
    public AutonomyPolicies(JdbcTemplate jdbc,TransactionTemplate tx,ProvisioningStore store,ConsoleSettings settings,DiscoveryStore discovery,Validator validator,ProvisioningProperties options) {
        this.jdbc=jdbc;db=new SqlDialect(jdbc);this.tx=tx;this.store=store;this.settings=settings;this.discovery=discovery;this.validator=validator;
        this.options=options;
    }
    public List<Policy> list() {
        return jdbc.query(db.sql("SELECT *,payload@text@ AS policy_json FROM agent_autonomy_policy WHERE platform_id=? ORDER BY updated_at DESC,id"),(rs,i)->new Policy(SqlDialect.uuid(rs,"id"),rs.getLong("revision"),store.decode(rs.getString("policy_json"),PolicyInput.class),rs.getString("cluster_api"),rs.getLong("connection_revision"),rs.getString("actor"),Instant.ofEpochMilli(rs.getLong("updated_at"))),options.platformInstanceId());
    }
    public Policy get(UUID id){return list().stream().filter(p->p.id().equals(id)).findFirst().orElseThrow(()->new ContextMismatch("托管策略不存在"));}
    public Policy save(UUID id,PolicyInput input,String actor) {
        if(!options.enabled())throw new ContextMismatch("请先配置并启用受权平台阶段协议");
        if(!validator.validate(input).isEmpty()||(ProvisioningStore.blank(input.siteId())&&ProvisioningStore.blank(input.projectId()))
                ||!ProvisioningStore.blank(input.siteId())&&!input.siteId().matches("[1-9][0-9]{0,18}"))throw new IllegalArgumentException("策略须指定工作空间及明确站点或项目");
        DiscoveryStore.validateNamespaces(List.of(input.namespace()));
        return tx.execute(s->{
            jdbc.update("INSERT INTO agent_provisioning_source(platform_id) VALUES(?) ON CONFLICT DO NOTHING",options.platformInstanceId());
            jdbc.queryForList(db.sql("SELECT platform_id FROM agent_provisioning_source WHERE platform_id=?@lock@"),options.platformInstanceId());
            jdbc.queryForList(db.sql("SELECT id FROM agent_console_connection WHERE id=?@lock@"),input.connectionId());
            var connection=settings.connection(input.connectionId());var source=discovery.source(input.connectionId());
            if(!source.namespaces().contains(input.namespace()))throw new ContextMismatch("策略命名空间未纳管，请先显式配置监控范围");
            var previous=list().stream().filter(p->p.id().equals(id)).findFirst();
            if(previous.isPresent()&&previous.get().revision()!=input.revision()||previous.isEmpty()&&input.revision()!=0)throw new ContextMismatch("策略已变化，请刷新");
            if(previous.isPresent()&&(!previous.get().scope().workspaceId().equals(input.workspaceId())
                    ||!Objects.equals(previous.get().scope().siteId(),input.siteId())||!Objects.equals(previous.get().scope().projectId(),input.projectId())
                    ||!previous.get().scope().connectionId().equals(input.connectionId())||!previous.get().scope().namespace().equals(input.namespace())))
                throw new ContextMismatch("策略身份和范围不可换址，请暂停旧策略后新建");
            for(var other:list())if(!other.id().equals(id)&&input.enabled()&&other.scope().enabled()
                    &&other.scope().workspaceId().equals(input.workspaceId())&&overlaps(input,other.scope()))throw new ContextMismatch("已有覆盖该范围的启用策略，请先暂停，避免多策略授权冲突");
            long revision=input.revision()+1;String cluster=DeploymentIdentity.endpoint(connection.apiUrl());long now=System.currentTimeMillis();
            if(previous.isEmpty())jdbc.update(db.sql("INSERT INTO agent_autonomy_policy(id,platform_id,revision,payload,cluster_api,connection_revision,actor,updated_at) VALUES(?,?,?,@json@,?,?,?,?)"),id,options.platformInstanceId(),revision,store.encode(input),cluster,connection.revision(),actor,now);
            else if(jdbc.update(db.sql("UPDATE agent_autonomy_policy SET revision=?,payload=@json@,cluster_api=?,connection_revision=?,actor=?,updated_at=? WHERE id=? AND revision=?"),revision,store.encode(input),cluster,connection.revision(),actor,now,id,input.revision())!=1)throw new ContextMismatch("策略已变化，请刷新");
            Policy result=get(id);
            jdbc.update(db.sql("INSERT INTO agent_autonomy_audit(id,policy_id,payload,actor,created_at) VALUES(?,?,@json@,?,?)"),UUID.randomUUID(),id,store.encode(result),actor,now);
            return result;
        });
    }
    private boolean overlaps(PolicyInput a,PolicyInput b) {
        return !(!ProvisioningStore.blank(a.siteId())&&!ProvisioningStore.blank(b.siteId())&&!a.siteId().equals(b.siteId()))
                &&!(!ProvisioningStore.blank(a.projectId())&&!ProvisioningStore.blank(b.projectId())&&!a.projectId().equals(b.projectId()));
    }
    public Optional<Policy> matching(Watch watch) {
        var stage=watch.stage();var matches=list().stream().filter(p->p.scope().enabled()&&p.scope().workspaceId().equals(stage.workspaceId())
                &&(ProvisioningStore.blank(p.scope().siteId())||p.scope().siteId().equals(stage.siteId()))
                &&(ProvisioningStore.blank(p.scope().projectId())||p.scope().projectId().equals(stage.projectId()))).toList();
        return matches.size()==1?Optional.of(matches.getFirst()):Optional.empty();
    }
    public void assertCurrent(Policy policy,Watch watch) {
        var current=get(policy.id());
        if(!current.scope().enabled()||current.revision()!=policy.revision()||watch.paused())throw new ContextMismatch("托管已暂停或策略版本已变化");
        var connection=settings.connection(policy.scope().connectionId());var source=discovery.source(connection.id());
        if(connection.revision()!=policy.connectionRevision()||!DeploymentIdentity.endpoint(connection.apiUrl()).equals(policy.clusterApiUrl()))throw new ContextMismatch("连接或凭据已变化，请重新核验托管策略");
        if(!source.enabled()||!source.namespaces().contains(policy.scope().namespace()))throw new ContextMismatch("监控范围已暂停或收回");
        if(!ProvisioningStore.blank(watch.stage().namespace())&&(!watch.stage().namespace().equals(policy.scope().namespace())
                ||!policy.clusterApiUrl().equals(DeploymentIdentity.endpoint(watch.stage().clusterApiUrl()))))throw new ContextMismatch("平台映射超出冻结连接与命名空间，待纳管");
        if(!matching(watch).map(p->p.id().equals(policy.id())).orElse(false))throw new ContextMismatch("托管策略不再匹配当前站点");
    }
}
