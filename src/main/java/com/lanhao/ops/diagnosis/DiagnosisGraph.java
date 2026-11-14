package com.lanhao.ops.diagnosis;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.command.*;
import com.lanhao.ops.command.CommandModel.*;
import com.lanhao.ops.persistence.RunStore;
import org.springframework.stereotype.Component;
import java.time.Instant;
import java.util.*;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** 图编排诊断与有审计的只读采证；恢复副作用由持久化状态机驱动。 */
@Component
public class DiagnosisGraph {
    private final CompiledGraph graph;
    private final ObjectMapper json;
    private final AgentProperties properties;
    private final java.util.concurrent.locks.ReentrantLock invocation=new java.util.concurrent.locks.ReentrantLock();
    public DiagnosisGraph(Redactor redactor, RecoveryPolicy policy, DiagnosticNarrator narrator,
            ObjectMapper json, AgentProperties properties) throws Exception {
        this(redactor,policy,narrator,json,properties,null);
    }
    public DiagnosisGraph(Redactor redactor, RecoveryPolicy policy, DiagnosticNarrator narrator,
            ObjectMapper json, AgentProperties properties,com.lanhao.ops.memory.MemoryRecall memory) throws Exception {
        this(redactor,policy,narrator,json,properties,memory,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public DiagnosisGraph(Redactor redactor, RecoveryPolicy policy, DiagnosticNarrator narrator,
            ObjectMapper json, AgentProperties properties,com.lanhao.ops.memory.MemoryRecall memory,KubectlWorkflow commands) throws Exception {
        this.json = json; this.properties = properties;
        StateGraph definition = new StateGraph("部署诊断", () -> {
            Map<String, KeyStrategy> strategies = new HashMap<>();
            for (String key : List.of("input", "safe", "evidence", "action", "diagnosis","memory","scope","commands","commandAnalysis","memoryScope")) strategies.put(key, new ReplaceStrategy());
            return strategies;
        });
        definition.addNode("sanitize", node_async(state -> Map.of("safe", redactor.clean(state.<String>value("input").orElseThrow()))));
        // 分类使用所有已脱敏证据，不能因模型上下文裁剪而丢弃后面的禁止条件。
        definition.addNode("classify", node_async(state -> Map.of("action", policy.classify(json.readValue(state.<String>value("evidence").orElseThrow(),Evidence.class)).name())));
        definition.addNode("recallMemory",node_async(state->Map.of("memory",json.writeValueAsString(memory==null
                ?new com.lanhao.ops.memory.MemoryRecall.Result("",List.of()):memory.find(state.<String>value("safe").orElseThrow(),state.<String>value("memoryScope").orElse("UNBOUND"))))));
        definition.addNode("generateCommands",node_async(state->{
            var recalled=json.readValue(state.<String>value("memory").orElseThrow(),com.lanhao.ops.memory.MemoryRecall.Result.class);
            Work work=commands==null?new Work(null,new Catalog(List.of(),""),List.of(),List.of(),List.of(),"命令采证未启用","",false)
                    :commands.generate(json.readValue(state.<String>value("scope").orElse("null"),Scope.class),state.<String>value("safe").orElseThrow()+"\n"+recalled.context());
            return Map.of("commands",json.writeValueAsString(work));
        }));
        definition.addNode("assessCommands",node_async(state->Map.of("commands",commands==null?state.<String>value("commands").orElseThrow():json.writeValueAsString(commands.assess(json.readValue(state.<String>value("commands").orElseThrow(),Work.class))))));
        definition.addNode("executeCommands",node_async(state->Map.of("commands",commands==null?state.<String>value("commands").orElseThrow():json.writeValueAsString(commands.execute(json.readValue(state.<String>value("commands").orElseThrow(),Work.class))))));
        definition.addNode("analyzeResults",node_async(state->{
            Analysis analysis=commands==null?new Analysis("命令采证未启用",false):commands.analyze(json.readValue(state.<String>value("commands").orElseThrow(),Work.class));
            // 新证据只能否决原授权，不能把人工动作提升为自动重试。
            String action=analysis.veto()||policy.hasBlockingEvidence(analysis.context())?Action.MANUAL_REPAIR.name():state.<String>value("action").orElseThrow();
            return Map.of("commandAnalysis",analysis.context(),"action",action);
        }));
        definition.addNode("diagnose", node_async(state -> {
            var recalled=json.readValue(state.<String>value("memory").orElseThrow(),com.lanhao.ops.memory.MemoryRecall.Result.class);
            Diagnosis result = narrator.explain(state.<String>value("safe").orElseThrow()+"\n<historical-memory>\n"+recalled.context()+"\n</historical-memory>\n<command-evidence>\n"+state.<String>value("commandAnalysis").orElseThrow()+"\n</command-evidence>",
                    Action.valueOf(state.<String>value("action").orElseThrow()));
            result=new Diagnosis(result.cause(),result.checks(),result.repairSteps(),result.verification(),result.source(),recalled.references(),
                    json.readValue(state.<String>value("commands").orElseThrow(),Work.class).results().stream().map(Execution::id).toList());
            return Map.of("diagnosis", json.writeValueAsString(result));
        }));
        definition.addEdge(StateGraph.START, "sanitize").addEdge("sanitize", "classify")
                .addEdge("classify", "recallMemory").addEdge("recallMemory","generateCommands")
                .addEdge("generateCommands","assessCommands").addEdge("assessCommands","executeCommands")
                .addEdge("executeCommands","analyzeResults").addEdge("analyzeResults","diagnose").addEdge("diagnose", StateGraph.END);
        graph = definition.compile();
        graph.setMaxIterations(14);
    }
    public Plan diagnose(Evidence evidence) {
        return execute(evidence,null,"UNBOUND");
    }
    public Plan diagnose(Evidence evidence,String memoryScope) {return execute(evidence,null,memoryScope);}
    public Plan diagnose(Run run,RunStore.Claim claim){return execute(run.evidence,Scope.of(run,claim),com.lanhao.ops.domain.DeploymentIdentity.scope(run));}
    private Plan execute(Evidence evidence,Scope scope,String memoryScope) {
        boolean locked=false;
        try {
            invocation.lockInterruptibly();locked=true;com.lanhao.ops.workflow.StepControl.check();
            Map<String,Object> input=new HashMap<>();input.put("input",evidence.reason()+"\n"+String.join("\n",evidence.facts())+"\n采证缺口="+evidence.issues());input.put("evidence",json.writeValueAsString(evidence));
            // 每次显式覆盖范围；只传证据的调用不得沿用此前调用的任务身份。
            input.put("scope",json.writeValueAsString(scope));
            input.put("memoryScope",memoryScope);
            OverAllState result = graph.invoke(input)
                    .orElseThrow();
            Action action = Action.valueOf(result.<String>value("action").orElseThrow());
            Diagnosis diagnosis = json.readValue(result.<String>value("diagnosis").orElseThrow(), Diagnosis.class);
            var supporting=evidence.items().stream().filter(EvidenceItem::decisive).limit(10).map(item->item.source()+" UID="+item.resourceUid()+" generation="+item.generation()+" "+item.reason()).toList();
            var opposing=evidence.items().stream().filter(item->new RecoveryPolicy().hasBlockingEvidence(item.reason()+"\n"+item.message())).limit(10).map(item->item.source()+" "+item.reason()).toList();
            var missing=evidence.issues().stream().limit(20).map(issue->issue.source()+" "+issue.errorType()).toList();
            return new Plan(UUID.randomUUID().toString(),action,diagnosis,action==Action.RETRY_TRANSIENT&&properties.autoRetry(),Instant.now(),
                    action==Action.RETRY_TRANSIENT?"platform-transient-retry":"manual-investigation",1,
                    new Assessment(diagnosis.cause(),supporting,opposing,missing,action,diagnosis.verification()));
        } catch (Exception failure) {
            if(failure instanceof InterruptedException)Thread.currentThread().interrupt();
            org.slf4j.LoggerFactory.getLogger(DiagnosisGraph.class).warn("诊断图未完成，已转人工；异常类型：{}",failure.getClass().getSimpleName());
            return new Plan(UUID.randomUUID().toString(), Action.MANUAL_REPAIR,
                    RunbookNarrator.fallback(Action.MANUAL_REPAIR, "诊断图异常，转人工"), false, Instant.now());
        }finally{if(locked)invocation.unlock();}
    }
}
