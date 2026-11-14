package com.lanhao.ops.console;

import com.lanhao.ops.diagnosis.DiagnosisGraph;
import com.lanhao.ops.domain.Model.Plan;
import com.lanhao.ops.workflow.StepControl;
import org.springframework.stereotype.Service;
import java.time.*;
import java.util.*;

@Service
public class DiscoveryService {
    private final DiscoveryStore store;
    private final DiscoveryGateway gateway;
    private final DiagnosisGraph graph;
    private final ReadOnlyEvidenceCollector collector;
    private AutonomyGuard autonomy;
    @org.springframework.beans.factory.annotation.Autowired
    public void autonomy(AutonomyGuard autonomy){this.autonomy=autonomy;}
    public DiscoveryService(DiscoveryStore store,DiscoveryGateway gateway,DiagnosisGraph graph,ReadOnlyEvidenceCollector collector){this.store=store;this.gateway=gateway;this.graph=graph;this.collector=collector;}
    public DiscoveryStore.Source scan(UUID connectionId,boolean force) {
        var claim=store.claimScan(connectionId,force);if(claim==null)return store.source(connectionId);
        try{store.scanned(claim,gateway.scan(claim.connection(),claim.namespaces()),"");}
        catch(RuntimeException failure){
            store.scanned(claim,List.of(),failure instanceof ConsoleFailure?failure.getMessage():"本轮同步失败，请检查连接和 CR 读取权限");
        }
        return store.source(connectionId);
    }
    public void processNext() {
        var work=store.claimWork();if(work==null)return;
        try(var control=new StepControl(Duration.ofSeconds(90))) {
            control.bind();
            if(autonomy!=null)autonomy.investigation(work.task());
            var connection=store.connection(work.task());
            var observation=gateway.read(connection,work.task().observation().target());Instant collectedAt=Instant.now();StepControl.check();
            // 只传证据，不构造工作流 Scope；不会沿用另一个集群的 kubectl 或恢复身份。
            store.connection(work.task());
            var evidence=collector.collect(connection,observation);StepControl.check();
            if(autonomy!=null)autonomy.investigation(work.task());
            store.collected(work,new DiscoveryStore.Report(collectedAt,observation,
                    new Plan(UUID.randomUUID().toString(),com.lanhao.ops.domain.Model.Action.MANUAL_REPAIR,
                            com.lanhao.ops.diagnosis.RunbookNarrator.fallback(com.lanhao.ops.domain.Model.Action.MANUAL_REPAIR,"采证已完成，分析尚未完成；不授予恢复权限"),false,Instant.now()),evidence));
            Plan result=graph.diagnose(evidence,com.lanhao.ops.domain.DeploymentIdentity.realScope(connection.apiUrl(),connection.namespace()));StepControl.check();
            gateway.read(store.connection(work.task()),work.task().observation().target());StepControl.check();
            if(autonomy!=null)autonomy.investigation(work.task());
            Plan report=new Plan(result.id(),result.action(),result.diagnosis(),false,result.createdAt(),result.runbookId(),result.runbookVersion(),result.assessment());
            store.finish(work,new DiscoveryStore.Report(collectedAt,observation,report,evidence),"");
        }catch(RuntimeException failure){
            store.finish(work,null,failure instanceof ConsoleFailure?failure.getMessage():"本次排查未完成，请检查连接或稍后手动重新排查");
        }
    }
}
