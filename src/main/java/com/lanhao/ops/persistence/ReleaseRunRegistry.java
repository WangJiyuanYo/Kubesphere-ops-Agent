package com.lanhao.ops.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.console.ProvisioningProperties;
import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.domain.Model.Run;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;
import java.util.function.Supplier;

/** 按稳定平台实例与发布周期永久保留唯一预算，终态和新 requestKey 均不重置。 */
@Component
public class ReleaseRunRegistry {
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final String platform;
    public ReleaseRunRegistry(JdbcTemplate jdbc,TransactionTemplate tx,ObjectMapper json,AgentProperties config,ProvisioningProperties options) {
        this.jdbc=jdbc;db=new SqlDialect(jdbc);this.tx=tx;this.json=json;
        platform=options.platformInstanceId()==null||options.platformInstanceId().isBlank()?
                config.platformBaseUrl()==null||config.platformBaseUrl().toString().isBlank()?"local-demo":DeploymentIdentity.endpoint(config.platformBaseUrl().toString()):options.platformInstanceId();
    }
    public Run unique(String provisioningId,String version,Supplier<Run> create) {
        if(version==null||version.isBlank())return create.get();
        return tx.execute(s->{
            jdbc.update("INSERT INTO agent_release_budget(platform_id,provisioning_id,deployment_version) VALUES(?,?,?) ON CONFLICT DO NOTHING",platform,provisioningId,version);
            jdbc.queryForList(db.sql("SELECT platform_id FROM agent_release_budget WHERE platform_id=? AND provisioning_id=? AND deployment_version=?@lock@"),platform,provisioningId,version);
            var linked=jdbc.query(db.sql("SELECT r.payload@text@ FROM agent_run r JOIN agent_release_budget b ON b.run_id=r.id WHERE b.platform_id=? AND b.provisioning_id=? AND b.deployment_version=?"),(rs,i)->decode(rs.getString(1)),platform,provisioningId,version);
            // 升级前账本只认领唯一记录；有历史冲突时拒绝创建新预算，交由人工核验。
            if(linked.isEmpty())linked=jdbc.query(db.sql("SELECT payload@text@ FROM agent_run WHERE provisioning_id=? AND payload->>'deploymentVersion'=? ORDER BY created_at,id"),(rs,i)->decode(rs.getString(1)),provisioningId,version);
            if(linked.size()>1)throw new IllegalStateException("同发布存在历史重复账本，须先人工核验预算");
            Run result=linked.isEmpty()?create.get():linked.getFirst();
            if(result==null)return null;
            jdbc.update("UPDATE agent_release_budget SET run_id=? WHERE platform_id=? AND provisioning_id=? AND deployment_version=?",result.id,platform,provisioningId,version);
            return result;
        });
    }
    private Run decode(String value){try{return json.readValue(value,Run.class);}catch(Exception failure){throw new IllegalStateException("发布预算账本不可读取");}}
}
