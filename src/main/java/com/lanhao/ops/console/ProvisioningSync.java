package com.lanhao.ops.console;

import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.util.*;

@Service
public class ProvisioningSync {
    public record Status(boolean enabled,String platformInstanceId,String warning,long checkedAt) {}
    private final ProvisioningGateway gateway;
    private final ProvisioningStore store;
    private final ProvisioningProperties options;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate tx;
    public ProvisioningSync(ProvisioningGateway gateway,ProvisioningStore store,ProvisioningProperties options,JdbcTemplate jdbc,TransactionTemplate tx) {
        this.gateway=gateway;this.store=store;this.options=options;this.jdbc=jdbc;this.tx=tx;
    }
    public Status status() {
        var rows=jdbc.query("SELECT warning,checked_at FROM agent_provisioning_source WHERE platform_id=?",(rs,i)->new Status(gateway.enabled(),options.platformInstanceId(),rs.getString(1),rs.getLong(2)),options.platformInstanceId());
        return rows.isEmpty()?new Status(gateway.enabled(),options.platformInstanceId(),gateway.enabled()?"等待首次阶段同步":"平台阶段协议尚未启用",0):rows.getFirst();
    }
    public Status sync() {
        if(!gateway.enabled())return status();
        UUID owner=UUID.randomUUID();long now=System.currentTimeMillis();
        boolean claimed=Boolean.TRUE.equals(tx.execute(s->{
            jdbc.update("INSERT INTO agent_provisioning_source(platform_id) VALUES(?) ON CONFLICT DO NOTHING",options.platformInstanceId());
            return jdbc.update("UPDATE agent_provisioning_source SET lease_owner=?,lease_until=? WHERE platform_id=? AND lease_until<=?",owner,now+60000,options.platformInstanceId(),now)==1;
        }));
        if(!claimed)return status();
        try {
            String cursor=jdbc.queryForObject("SELECT cursor_value FROM agent_provisioning_source WHERE platform_id=?",String.class,options.platformInstanceId());
            boolean more=false;long deadline=System.nanoTime()+java.time.Duration.ofSeconds(40).toNanos();
            for(int i=0;i<options.maxPages();i++) {
                if(System.nanoTime()>deadline)throw new ConsoleFailure("阶段补偿同步超过本轮时限，保留已提交分页");
                var page=gateway.page(cursor);
                String next=page.nextCursor();
                tx.executeWithoutResult(s->{
                    var db=new SqlDialect(jdbc);
                    jdbc.queryForList(db.sql("SELECT platform_id FROM agent_provisioning_source WHERE platform_id=?@lock@"),options.platformInstanceId());
                    if(!Boolean.TRUE.equals(jdbc.queryForObject("SELECT EXISTS(SELECT 1 FROM agent_provisioning_source WHERE platform_id=? AND lease_owner=? AND lease_until>?)",Boolean.class,options.platformInstanceId(),owner,System.currentTimeMillis())))throw new IllegalStateException("阶段同步租约过期");
                    for(var event:page.items())store.ingest(event,gateway.authorizationHash());
                    jdbc.update("UPDATE agent_provisioning_source SET cursor_value=? WHERE platform_id=? AND lease_owner=?",next,options.platformInstanceId(),owner);
                });
                cursor=next;more=page.hasMore();if(!more)break;
            }
            jdbc.update("UPDATE agent_provisioning_source SET checked_at=?,warning=?,lease_owner=NULL,lease_until=0 WHERE platform_id=? AND lease_owner=?",System.currentTimeMillis(),more?"本轮达到页数上限；保留全部账本，下轮从游标继续":"",options.platformInstanceId(),owner);
        }catch(RuntimeException failure) {
            String message=failure instanceof ConsoleFailure?failure.getMessage():"阶段同步失败，保留上次事实，观察已降级";
            tx.executeWithoutResult(s->{
                int changed=jdbc.update("UPDATE agent_provisioning_source SET warning=?,lease_owner=NULL,lease_until=0 WHERE platform_id=? AND lease_owner=?",message,options.platformInstanceId(),owner);
                if(changed==1) {
                    jdbc.update("UPDATE agent_provisioning_watch SET warning=?,authorized_at=0 WHERE platform_id=?",message,options.platformInstanceId());
                    if(failure instanceof ProvisioningGateway.CursorExpired)jdbc.update("UPDATE agent_provisioning_source SET cursor_value='' WHERE platform_id=?",options.platformInstanceId());
                }
            });
        }
        return status();
    }
}
