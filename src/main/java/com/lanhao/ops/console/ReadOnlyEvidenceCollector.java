package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.workflow.StepControl;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

/** 页面只读连接的目标采证；不接受模型生成的 URL、命令或资源范围。 */
@Component
public class ReadOnlyEvidenceCollector {
    public interface ReadAudit {
        default void before(String path) {}
        default void before(String path,String displayPath) { before(path); }
        default void after(String path,String outcome) {}
    }
    private final ConsoleSettings settings;
    private final ObjectMapper json;
    private final Redactor redactor;
    public ReadOnlyEvidenceCollector(ConsoleSettings settings,ObjectMapper json,Redactor redactor) {
        this.settings=settings;this.json=json;this.redactor=redactor;
    }

    public Evidence collect(ConsoleSettings.Connection connection,DiscoveryGateway.Observation observation) {
        return collect(connection,observation,Duration.ofSeconds(20));
    }
    public Evidence collect(ConsoleSettings.Connection connection,DiscoveryGateway.Observation observation,Duration timeout) {
        return collect(connection,observation,timeout,new ReadAudit() {});
    }
    public Evidence collect(ConsoleSettings.Connection connection,DiscoveryGateway.Observation observation,Duration timeout,ReadAudit audit) {
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(20))>0)throw new IllegalArgumentException("采证预算无效");
        if(!connection.namespace().equals(observation.target().namespace()))throw new ContextMismatch("采证目标与连接命名空间不一致");
        Evidence base=observation.evidence();
        var budget=new Budget(connection,timeout,audit);
        var facts=new ArrayList<>(base.facts());var items=new ArrayList<>(base.items());var issues=new ArrayList<CollectionIssue>();
        var resources=new ArrayList<JsonNode>();String ns=connection.namespace();
        for(String path:List.of("/apis/apps/v1/namespaces/"+ns+"/deployments", "/apis/apps/v1/namespaces/"+ns+"/statefulsets",
                "/apis/batch/v1/namespaces/"+ns+"/jobs", "/apis/apps/v1/namespaces/"+ns+"/replicasets")) {
            try { resources.addAll(budget.list(path)); }
            catch(Unavailable failure){issues.add(new CollectionIssue("RELATED/"+path.substring(path.lastIndexOf('/')+1),observation.target().uid(),failure.code));}
        }
        var owners=new HashSet<>(Set.of(observation.target().uid()));
        // 固定点展开，不依赖资源列表顺序，也不以同名或可伪造标签授予归属。
        for(int pass=0;pass<8;pass++) {
            int before=owners.size();for(var resource:resources)if(owned(resource,owners)&&valid(resource,ns))owners.add(uid(resource));
            if(before==owners.size())break;
            if(pass==7)issues.add(new CollectionIssue("OWNERSHIP",observation.target().uid(),"OWNER_DEPTH_LIMIT"));
        }
        var related=resources.stream().filter(r->valid(r,ns)&&owners.contains(uid(r)))
                .sorted(Comparator.comparingInt((JsonNode r)->workloadFailed(r)?0:1)
                        .thenComparing(r->r.path("metadata").path("name").asText())).toList();
        var focusOwners=new HashSet<String>();
        for(var resource:related)if(workloadFailed(resource)||observation.message().contains(resource.path("metadata").path("name").asText()))focusOwners.add(uid(resource));
        // 故障焦点只能在已通过 ownerReferences 证明的归属内传播，名称提示不能授予范围。
        for(int pass=0;pass<8;pass++){int before=focusOwners.size();for(var resource:related)if(owned(resource,focusOwners))focusOwners.add(uid(resource));if(before==focusOwners.size())break;}
        for(var resource:related.stream().limit(8).toList()) {
            var status=resource.path("status");String name=resource.path("metadata").path("name").asText();
            String detail=budget.safe("工作负载="+name+" failed="+status.path("failed").asInt()+" succeeded="+status.path("succeeded").asInt()+" readyReplicas="+status.path("readyReplicas").asInt()
                    +" conditions="+status.path("conditions"));
            detail=detail.substring(0,Math.min(detail.length(),2000));
            facts.add(detail);items.add(new EvidenceItem("WORKLOAD/"+name,uid(resource),observation.target().generation(),Instant.now(),workloadFailed(resource)?"WORKLOAD_FAILED":"WORKLOAD_STATUS",detail,false));
        }
        if(related.size()>8)issues.add(new CollectionIssue("WORKLOADS",observation.target().uid(),"WORKLOAD_SAMPLE_LIMIT"));
        List<JsonNode> pods=List.of();
        try {
            var eligible=budget.list("/api/v1/namespaces/"+ns+"/pods").stream().filter(p->valid(p,ns)&&owned(p,owners))
                .sorted(Comparator.comparingInt((JsonNode p)->owned(p,focusOwners)?0:1).thenComparingInt(ReadOnlyEvidenceCollector::podPriority)
                        .thenComparing(p->p.path("metadata").path("name").asText())).toList();
            pods=eligible.stream().limit(4).toList();
            if(eligible.size()>4)issues.add(new CollectionIssue("PODS",observation.target().uid(),"POD_SAMPLE_LIMIT"));
        }
        catch(Unavailable failure){issues.add(new CollectionIssue("PODS",observation.target().uid(),failure.code));}
        if(pods.isEmpty()&&!base.ready())issues.add(new CollectionIssue("OWNERSHIP",observation.target().uid(),"NO_OWNED_PODS"));
        for(var pod:pods) {
            owners.add(uid(pod));String name=pod.path("metadata").path("name").asText();
            facts.add(budget.safe("Pod="+name+" phase="+pod.path("status").path("phase").asText()+" ready="+healthy(pod)));
            for(var container:containers(pod)) {
                var state=container.path("state");String reason=state.path("waiting").path("reason").asText(
                        state.path("terminated").path("reason").asText("Running"));
                String detail=budget.safe("容器="+name+"/"+container.path("name").asText()+" reason="+reason
                        +" restartCount="+container.path("restartCount").asInt()+" message="+state.path("waiting").path("message").asText()
                        +" exitCode="+state.path("terminated").path("exitCode").asText("")
                        +" lastReason="+container.path("lastState").path("terminated").path("reason").asText());
                facts.add(detail);items.add(new EvidenceItem("CONTAINER/"+name+"/"+container.path("name").asText(),uid(pod),
                        observation.target().generation(),Instant.now(),budget.safe(reason),detail,false));
            }
        }
        List<String> eventOwners=new ArrayList<>();eventOwners.add(observation.target().uid());
        pods.stream().map(ReadOnlyEvidenceCollector::uid).forEach(eventOwners::add);
        owners.stream().filter(u->!eventOwners.contains(u)).sorted(Comparator.comparingInt((String u)->focusOwners.contains(u)?0:1).thenComparing(u->u))
                .limit(8-eventOwners.size()).forEach(eventOwners::add);
        for(String owner:eventOwners) {
            try {
                var events=budget.list("/api/v1/namespaces/"+ns+"/events?fieldSelector="+encode("involvedObject.uid="+owner));
                events.stream().filter(e->owner.equals(e.path("involvedObject").path("uid").asText()))
                        .sorted(Comparator.comparing((JsonNode e)->e.path("lastTimestamp").asText(),Comparator.reverseOrder())).limit(5)
                        .forEach(e->{
                            String reason=budget.safe(e.path("reason").asText()),message=budget.safe(e.path("message").asText());
                            facts.add("Event uid="+owner+" reason="+reason+" "+message);
                            items.add(new EvidenceItem("EVENT/"+owner,owner,observation.target().generation(),Instant.now(),reason,message,false));
                        });
            }catch(Unavailable failure){issues.add(new CollectionIssue("EVENTS",owner,failure.code));}
        }
        int logs=0;
        for(var pod:pods)for(var container:containers(pod)) {
            if(logs>=4)break;
            String name=pod.path("metadata").path("name").asText(),containerName=container.path("name").asText();
            if(!containerName.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")
                    || !(container.path("state").has("running")||container.path("state").has("terminated")||container.path("lastState").has("terminated")))continue;
            boolean restarted=container.path("restartCount").asInt()>0&&container.path("lastState").has("terminated");
            for(boolean previous:restarted?List.of(false,true):List.of(false)) {
                if(logs++>=4)break;
                try {
                    if(!samePod(budget,ns,name,uid(pod)))throw new Unavailable("POD_IDENTITY_CHANGED");
                    String log=budget.safe(budget.text("/api/v1/namespaces/"+ns+"/pods/"+name+"/log?container="+encode(containerName)+"&tailLines=60&limitBytes=4096"+(previous?"&previous=true":"")));
                    if(!samePod(budget,ns,name,uid(pod)))throw new Unavailable("POD_IDENTITY_CHANGED");
                    facts.add("Log="+name+"/"+containerName+(previous?"（上次退出）":"")+"\n"+log);
                    items.add(new EvidenceItem("LOG/"+name+"/"+containerName+(previous?"/previous":""),uid(pod),observation.target().generation(),Instant.now(),"",log,false));
                }catch(Unavailable failure){issues.add(new CollectionIssue("LOG/"+name+"/"+containerName,uid(pod),failure.code));}
            }
        }
        return new Evidence(base.observedAt(),base.ready(),base.failed(),base.fingerprint(),base.reason(),List.copyOf(facts),List.copyOf(items),List.copyOf(issues));
    }

    /** A connection-only conversation still reads actual failure details, not just Pod phases. */
    public Evidence collectNamespace(ConsoleSettings.Connection connection,String question,Duration timeout,ReadAudit audit) {
        if(timeout.isNegative()||timeout.isZero()||timeout.compareTo(Duration.ofSeconds(20))>0)throw new IllegalArgumentException("采证预算无效");
        var budget=new Budget(connection,timeout,audit);
        var facts=new ArrayList<String>();var issues=new ArrayList<CollectionIssue>();
        String ns=connection.namespace();Instant at=Instant.now();
        try {
            var listed=budget.list("/api/v1/namespaces/"+ns+"/pods");
            var pods=listed.stream().filter(p->valid(p,ns)).toList();
            if(listed.size()!=pods.size())issues.add(new CollectionIssue("PODS",ns,"INVALID_POD_IDENTITY（部分对象未采纳）"));
            var named=pods.stream().filter(p->java.util.regex.Pattern.compile("(?<![a-zA-Z0-9.-])"+java.util.regex.Pattern.quote(p.path("metadata").path("name").asText())+"(?![a-zA-Z0-9.-])").matcher(question).find()).toList();
            var candidates=(named.isEmpty()?pods:named).stream().sorted(Comparator.comparingInt(ReadOnlyEvidenceCollector::podPriority)
                    .thenComparing(p->p.path("metadata").path("name").asText())).toList();
            facts.add("真实命名空间="+ns+"；读取 Pod 数="+pods.size()+"；"+(named.isEmpty()?"优先采集异常 Pod":"匹配问题中明确的 Pod 名称"));
            if(candidates.size()>2)issues.add(new CollectionIssue("PODS",ns,"POD_SAMPLE_LIMIT（本轮仅详细检查前2个，可指定Pod名称继续）"));
            for(var pod:candidates.stream().limit(2).toList()) {
                String name=pod.path("metadata").path("name").asText(),owner=uid(pod);
                facts.add(budget.safe("Pod="+name+" uid="+owner+" phase="+pod.path("status").path("phase").asText()+" Ready="+healthy(pod)));
                for(var condition:pod.path("status").path("conditions")) {
                    if(!"True".equals(condition.path("status").asText()))facts.add(budget.safe("Condition="+condition.path("type").asText()+" "+condition.path("reason").asText()+" "+condition.path("message").asText()));
                }
                for(var container:containers(pod).stream().limit(2).toList()) {
                    String containerName=container.path("name").asText();
                    facts.add(budget.safe("Container="+name+"/"+containerName+" restartCount="+container.path("restartCount").asInt()+" state="+container.path("state")+" lastState="+container.path("lastState")));
                }
                try {
                    if(!samePod(budget,ns,name,owner)){issues.add(new CollectionIssue("POD/"+name,owner,"POD_IDENTITY_CHANGED"));continue;}
                }catch(Unavailable failure){issues.add(new CollectionIssue("POD/"+name,owner,failure.code));continue;}
                if(containers(pod).size()>2)issues.add(new CollectionIssue("CONTAINERS/"+name,owner,"CONTAINER_SAMPLE_LIMIT（仅检查前2个异常优先容器）"));
                try {
                    var events=budget.list("/api/v1/namespaces/"+ns+"/events?fieldSelector="+encode("involvedObject.uid="+owner));
                    events.stream().filter(e->owner.equals(e.path("involvedObject").path("uid").asText()))
                            .sorted(Comparator.comparing((JsonNode e)->e.path("lastTimestamp").asText(),Comparator.reverseOrder())).limit(4)
                            .forEach(e->facts.add(budget.safe("Event="+name+" "+e.path("reason").asText()+" "+e.path("message").asText())));
                }catch(Unavailable failure){issues.add(new CollectionIssue("EVENTS/"+name,owner,failure.code));}
                for(var container:containers(pod).stream().limit(2).toList()) {
                    String containerName=container.path("name").asText();
                    if(!containerName.matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?"))continue;
                    boolean restarted=container.path("restartCount").asInt()>0&&container.path("lastState").has("terminated");
                    for(boolean previous:restarted?List.of(false,true):List.of(false)) {
                        try {
                            if(!samePod(budget,ns,name,owner))throw new Unavailable("POD_IDENTITY_CHANGED");
                            String log=budget.safe(budget.text("/api/v1/namespaces/"+ns+"/pods/"+name+"/log?container="+encode(containerName)
                                    +"&tailLines=60&limitBytes=4096"+(previous?"&previous=true":"")));
                            if(!samePod(budget,ns,name,owner))throw new Unavailable("POD_IDENTITY_CHANGED");
                            facts.add("Log="+name+"/"+containerName+(previous?"（上次退出）":"（当前）")+"\n"+log.substring(0,Math.min(log.length(),1200)));
                        }catch(Unavailable failure){issues.add(new CollectionIssue("LOG/"+name+"/"+containerName+(previous?"/previous":""),owner,failure.code));}
                    }
                }
            }
            if(pods.isEmpty())facts.add("本轮 Pod 列表为空；不能据此认定部署成功。");
        }catch(Unavailable failure){issues.add(new CollectionIssue("PODS",ns,failure.code));}
        return new Evidence(at,false,false,"chat:"+ns+":"+at,"命名空间只读排查",List.copyOf(facts),List.of(),List.copyOf(issues));
    }
    private boolean samePod(Budget budget,String namespace,String name,String expectedUid) {
        try {
            var current=json.readTree(budget.read("/api/v1/namespaces/"+namespace+"/pods/"+name,1_048_576));
            return current!=null&&valid(current,namespace)&&expectedUid.equals(uid(current));
        }catch(java.io.IOException failure){throw new Unavailable("INVALID_JSON");}
    }

    private static List<JsonNode> containers(JsonNode pod) {
        var containers=new ArrayList<JsonNode>();
        pod.path("status").path("initContainerStatuses").forEach(containers::add);
        pod.path("status").path("containerStatuses").forEach(containers::add);
        return containers.stream().sorted(Comparator.comparingInt(ReadOnlyEvidenceCollector::containerPriority)
                .thenComparing(c->c.path("name").asText())).limit(8).toList();
    }
    private static int containerPriority(JsonNode container) {
        if(container.path("state").has("waiting")||container.path("state").path("terminated").path("exitCode").asInt()!=0
                ||container.path("lastState").path("terminated").path("exitCode").asInt()!=0)return 0;
        return container.path("state").has("running")?1:2;
    }
    private static boolean healthy(JsonNode pod) {
        for(var condition:pod.path("status").path("conditions"))
            if("Ready".equals(condition.path("type").asText())&&"True".equals(condition.path("status").asText()))return true;
        return false;
    }
    private static int podPriority(JsonNode pod) {
        String phase=pod.path("status").path("phase").asText();
        if("Failed".equals(phase))return 0;
        for(var container:containers(pod))if(container.path("state").has("waiting")
                ||container.path("state").path("terminated").path("exitCode").asInt(0)!=0)return 0;
        if("Succeeded".equals(phase))return 3;
        return healthy(pod)?2:1;
    }
    private static boolean workloadFailed(JsonNode resource) {
        var status=resource.path("status");
        for(var condition:status.path("conditions"))if("Complete".equals(condition.path("type").asText())&&"True".equals(condition.path("status").asText()))return false;
        for(var condition:status.path("conditions"))if("Failed".equals(condition.path("type").asText())&&"True".equals(condition.path("status").asText())
                ||"ProgressDeadlineExceeded".equals(condition.path("reason").asText()))return true;
        return status.path("failed").asInt()>0;
    }
    private static String uid(JsonNode resource){return resource.path("metadata").path("uid").asText();}
    private static boolean valid(JsonNode resource,String namespace) {
        return namespace.equals(resource.path("metadata").path("namespace").asText())
                &&!resource.path("metadata").hasNonNull("deletionTimestamp")&&uid(resource).matches("[A-Za-z0-9_-]{1,100}")
                &&resource.path("metadata").path("name").asText().matches("[a-z0-9]([-a-z0-9.]{0,251}[a-z0-9])?");
    }
    private static boolean owned(JsonNode resource,Set<String> owners) {
        for(var reference:resource.path("metadata").path("ownerReferences"))if(owners.contains(reference.path("uid").asText()))return true;
        return false;
    }
    private static String encode(String text){return URLEncoder.encode(text,StandardCharsets.UTF_8);}
    private static class Unavailable extends RuntimeException {
        final String code;
        Unavailable(String code){super(code);this.code=code;}
    }
    private class Budget {
        final ConsoleSettings.Connection connection;
        final RestClient client;
        final String token;
        final long deadline;
        final ReadAudit audit;
        int requests;
        Budget(ConsoleSettings.Connection connection,Duration timeout,ReadAudit audit) {
            deadline=System.nanoTime()+timeout.toNanos();
            this.connection=connection;token=settings.connectionToken(connection);this.audit=audit;
            var http=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).followRedirects(HttpClient.Redirect.NEVER);
            String ca=settings.connectionCaCertificate(connection);if(!ca.isBlank())http.sslContext(ConnectionTls.context(ca));
            var factory=new JdkClientHttpRequestFactory(http.build());factory.setReadTimeout(Duration.ofSeconds(2));
            client=RestClient.builder().requestFactory(factory).build();
        }
        String safe(String value){return redactor.clean(token.isBlank()?value:value.replace(token,"[已脱敏]").replace(encode(token),"[已脱敏]"));}
        byte[] read(String path,int max) {
            StepControl.check();
            if(System.nanoTime()>deadline||++requests>24)throw new Unavailable("COLLECTION_BUDGET");
            audit.before(path,safe(path));String outcome="NETWORK_OR_TLS";
            try {
                var request=client.get().uri(URI.create(connection.apiUrl()+path));
                if(!token.isBlank())request.header("Authorization","Bearer "+token);
                byte[] data=request.exchange((req,response)->{
                    int code=response.getStatusCode().value();if(code<200||code>=300)throw new Unavailable("HTTP_"+code);
                    byte[] bytes=response.getBody().readNBytes(max+1);if(bytes.length>max)throw new Unavailable("RESPONSE_LIMIT");
                    return bytes;
                });
                StepControl.check();if(System.nanoTime()>deadline)throw new Unavailable("COLLECTION_BUDGET");
                outcome="HTTP_200";
                return data;
            }catch(Unavailable failure){outcome=failure.code;throw failure;}
            catch(RuntimeException failure){StepControl.check();throw new Unavailable("NETWORK_OR_TLS");}
            finally{audit.after(path,outcome);}
        }
        String text(String path){return new String(read(path,4096),StandardCharsets.UTF_8);}
        List<JsonNode> list(String path) {
            var result=new ArrayList<JsonNode>();var seen=new HashSet<String>();String continuation="";
            do {
                try {
                    JsonNode page=json.readTree(read(path+(path.contains("?")?"&":"?")+"limit=100"
                            +(continuation.isBlank()?"":"&continue="+encode(continuation)),1_048_576));
                    if(page==null||!page.path("items").isArray())throw new Unavailable("INVALID_LIST");
                    page.path("items").forEach(result::add);
                    if(result.size()>500)throw new Unavailable("RESOURCE_LIMIT");
                    continuation=page.path("metadata").path("continue").asText("");
                    if(continuation.length()>4096||!continuation.isBlank()&&!seen.add(continuation))throw new Unavailable("PAGINATION_INVALID");
                }catch(java.io.IOException failure){throw new Unavailable("INVALID_JSON");}
            }while(!continuation.isBlank());
            return result;
        }
    }
}
