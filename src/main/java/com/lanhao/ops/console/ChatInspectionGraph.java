package com.lanhao.ops.console;

import com.alibaba.cloud.ai.graph.*;
import com.alibaba.cloud.ai.graph.state.strategy.ReplaceStrategy;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;
import static com.alibaba.cloud.ai.graph.action.AsyncNodeAction.node_async;

/** Real reads use the selected console connection, independently of the recovery process mode. */
@Component
public class ChatInspectionGraph {
    public record Plan(UUID connectionId,String origin,String namespace,Target target,List<String> operations) {}
    public record Request(int sequence,String operation,String request,String state,String result,Instant at) {}
    public record Report(UUID turnId,Plan plan,String stage,List<Request> requests,String evidence,List<String> issues,Instant at) {}
    private final ChatInspectionStore audit;
    private final ChatContextGuard guard;
    private final DiscoveryStore discovery;
    private final DiscoveryGateway gateway;
    private final ReadOnlyEvidenceCollector collector;
    private final ObjectMapper json;
    private final Redactor redactor;
    public ChatInspectionGraph(ChatInspectionStore audit,ChatContextGuard guard,DiscoveryStore discovery,DiscoveryGateway gateway,
            ReadOnlyEvidenceCollector collector,ObjectMapper json,Redactor redactor) {
        this.audit=audit;this.guard=guard;this.discovery=discovery;this.gateway=gateway;
        this.collector=collector;this.json=json;this.redactor=redactor;
    }
    public Report inspect(ChatStore.Claim claim,ChatStore.Session session,String question) {
        if(!claim.turn().includeCluster()||!claim.turn().sessionId().equals(session.id())||session.connectionId()==null)
            throw new ContextMismatch("本轮未授权当前会话的集群采证");
        guard.validate(session);audit.guard(claim);
        var connection=guard.connection(session);
        Target target=session.discoveryId()==null?null:discovery.task(session.discoveryId()).observation().target();
        Plan expected=new Plan(connection.id(),DiscoveryGateway.origin(connection),connection.namespace(),target,
                target==null?List.of("GET_POD_STATUS","GET_EVENTS_BY_UID","GET_BOUNDED_CONTAINER_LOGS")
                        :List.of("GET_RELATED_WORKLOADS","GET_POD_STATUS","GET_EVENTS_BY_UID","GET_BOUNDED_CONTAINER_LOGS"));
        // Invocation-local graph and ledger: no shared mutable state or inherited prior scope.
        var ledger=new Ledger(claim,session,connection,expected);
        try {
            var graph=new StateGraph("对话只读排查",()->{
                Map<String,KeyStrategy> keys=new HashMap<>();
                for(String key:List.of("scope","plan","evidence","report"))keys.put(key,new ReplaceStrategy());
                return keys;
            });
            graph.addNode("generateCommands",node_async(state->{
                Plan plan=json.readValue(state.<String>value("scope").orElseThrow(),Plan.class);
                return Map.of("plan",json.writeValueAsString(plan));
            }));
            graph.addNode("assessCommands",node_async(state->{
                Plan plan=json.readValue(state.<String>value("plan").orElseThrow(),Plan.class);
                if(!expected.equals(plan))throw new ContextMismatch("排查计划偏离冻结目标，未执行");
                ledger.check();ledger.save("APPROVED","",List.of());
                return Map.of();
            }));
            graph.addNode("executeCommands",node_async(state->{
                ledger.check();
                Evidence evidence;
                if(target==null) evidence=collector.collectNamespace(connection,question,Duration.ofSeconds(8),ledger);
                else {
                    var observation=gateway.readForChat(connection,target);
                    evidence=collector.collect(connection,observation,Duration.ofSeconds(8),ledger);
                    gateway.readForChat(guard.connection(session),target);
                }
                ledger.check();
                return Map.of("evidence",json.writeValueAsString(evidence));
            }));
            graph.addNode("analyzeResults",node_async(state->{
                Evidence evidence=json.readValue(state.<String>value("evidence").orElseThrow(),Evidence.class);
                var gaps=evidence.issues().stream().map(issue->issue.source()+"："+issue.errorType()+hint(issue)).toList();
                String content=redactor.clean("本轮真实集群采证时间："+evidence.observedAt()+"\n采证缺口："+gaps+"\n"+String.join("\n",evidence.facts()));
                Report report=ledger.save(gaps.isEmpty()?"COMPLETED":"PARTIAL",content,gaps);
                return Map.of("report",json.writeValueAsString(report));
            }));
            graph.addEdge(StateGraph.START,"generateCommands").addEdge("generateCommands","assessCommands")
                    .addEdge("assessCommands","executeCommands").addEdge("executeCommands","analyzeResults").addEdge("analyzeResults",StateGraph.END);
            var compiled=graph.compile();compiled.setMaxIterations(8);
            return json.readValue(compiled.invoke(Map.of("scope",json.writeValueAsString(expected))).orElseThrow()
                    .<String>value("report").orElseThrow(),Report.class);
        }catch(Exception failure) {
            Throwable root=failure;
            while(root.getCause()!=null&&root.getCause()!=root)root=root.getCause();
            try {
                ledger.check();ledger.save("FAILED","",List.of(root instanceof ConsoleFailure safe?safe.getMessage():"只读采证未完成"));
            }catch(RuntimeException stale){/* An expired or changed scope cannot rewrite the audit. */}
            if(root instanceof ContextMismatch mismatch)throw mismatch;
            if(root instanceof ConsoleFailure safe)throw safe;
            throw new ConsoleFailure("只读排查未完成；已保存的请求可在本轮排查记录查看，不会自动重放");
        }
    }
    private static String hint(CollectionIssue issue) {
        if(!issue.errorType().equals("HTTP_403"))return "";
        String resource=issue.source().startsWith("LOG/")?"pods/log 的 get":issue.source().startsWith("EVENT")?"events 的 list":"对应资源的 get/list";
        return "（当前连接身份缺少 "+resource+" 权限；请管理员核对该命名空间 Role/RoleBinding，不需要授予 cluster-admin）";
    }
    static void reviewRequest(String path,String namespace) {
        URI uri=URI.create(path);
        String ns=Pattern.quote(namespace);
        if(uri.isAbsolute()||uri.getRawAuthority()!=null||uri.getFragment()!=null||!uri.normalize().equals(uri)
                ||!uri.getPath().matches("(?:/api/v1/namespaces/"+ns+"/(?:pods(?:/[a-z0-9][a-z0-9.-]*(?:/log)?)?|events)|/apis/(?:apps|batch)/v1/namespaces/"+ns+"/(?:deployments|statefulsets|jobs|replicasets))"))
            throw new ContextMismatch("采证请求不在本轮命名空间只读白名单内");
        var parameters=new HashMap<String,String>();
        for(String part:Objects.toString(uri.getRawQuery(),"").split("&")) {
            if(part.isEmpty())continue;
            String[] pair=part.split("=",2);
            String key=URLDecoder.decode(pair[0],StandardCharsets.UTF_8),value=pair.length==2?URLDecoder.decode(pair[1],StandardCharsets.UTF_8):"";
            if(parameters.putIfAbsent(key,value)!=null)throw new ContextMismatch("采证参数重复，未执行");
        }
        boolean log=uri.getPath().endsWith("/log"),events=uri.getPath().endsWith("/events");
        Set<String> allowed=log?Set.of("container","tailLines","limitBytes","previous"):events?Set.of("limit","continue","fieldSelector"):Set.of("limit","continue");
        if(!allowed.containsAll(parameters.keySet())
                ||parameters.containsKey("limit")&&!parameters.get("limit").equals("100")
                ||parameters.getOrDefault("continue","").length()>4096
                ||log&&(!parameters.getOrDefault("container","").matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
                    ||!parameters.getOrDefault("tailLines","").equals("60")||!parameters.getOrDefault("limitBytes","").equals("4096")
                    ||parameters.containsKey("previous")&&!parameters.get("previous").equals("true"))
                ||events&&!parameters.getOrDefault("fieldSelector","").matches("involvedObject.uid=[A-Za-z0-9_-]{1,100}")) {
            throw new ContextMismatch("采证参数不在固定白名单或输出预算内");
        }
    }
    private final class Ledger implements ReadOnlyEvidenceCollector.ReadAudit {
        final ChatStore.Claim claim;final ChatStore.Session session;final ConsoleSettings.Connection connection;final Plan plan;
        final List<Request> requests=new ArrayList<>();
        Ledger(ChatStore.Claim claim,ChatStore.Session session,ConsoleSettings.Connection connection,Plan plan) {
            this.claim=claim;this.session=session;this.connection=connection;this.plan=plan;
        }
        void check(){
            audit.guard(claim);guard.validate(session);
            var current=guard.connection(session);
            if(current.revision()!=connection.revision()||!DiscoveryGateway.origin(current).equals(plan.origin()))
                throw new ContextMismatch("连接在排查期间发生变化，停止采证；请重新选择目标");
        }
        public void before(String path,String displayPath) {
            check();reviewRequest(path,plan.namespace());
            String operation=path.contains("/log?")?"LOGS":path.contains("/events?")?"EVENTS":"GET";
            requests.add(new Request(requests.size()+1,operation,"GET "+redactor.clean(displayPath),"RUNNING","执行意图已持久化；结果尚未确认",Instant.now()));
            save("RUNNING","",List.of());
        }
        public void after(String path,String outcome) {
            check();int index=requests.size()-1;Request previous=requests.get(index);
            requests.set(index,new Request(previous.sequence(),previous.operation(),previous.request(),outcome.equals("HTTP_200")?"SUCCEEDED":"FAILED",outcome,Instant.now()));
            save("RUNNING","",List.of());
        }
        Report save(String stage,String evidence,List<String> issues) {
            Report report=new Report(claim.turn().id(),plan,stage,List.copyOf(requests),evidence,issues,Instant.now());
            audit.save(claim,report);return report;
        }
    }
}
