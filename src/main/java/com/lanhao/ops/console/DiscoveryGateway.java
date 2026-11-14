package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.util.*;

/** 只读取四类 Operator CR 的身份与状态；不存储 spec、Secret 或完整上游正文。 */
@Component
public class DiscoveryGateway {
    public record Observation(Target target,String releaseVersion,String projectId,String environment,
            String phase,long observedGeneration,String reason,String message) {
        public Evidence evidence() {
            boolean current=observedGeneration==target.generation();
            boolean failed=current&&(phase.equalsIgnoreCase("Failed")||phase.equalsIgnoreCase("Degraded"));
            String fact=target.kind()+"/"+target.namespace()+"/"+target.name()+" UID="+target.uid()+" generation="+target.generation()
                    +" observedGeneration="+observedGeneration+" phase="+phase+" releaseVersion="+releaseVersion
                    +" reason="+reason+" message="+message;
            Instant now=Instant.now();
            return new Evidence(now,current&&phase.equalsIgnoreCase("Ready"),failed,target.uid()+":"+target.generation()+":"+phase,
                    reason.isBlank()?phase:reason,List.of(fact),List.of(new EvidenceItem("CR/"+target.kind()+"/"+target.name(),target.uid(),
                    target.generation(),now,reason,message,failed)),List.of());
        }
    }
    private final ConsoleSettings settings;
    private final ObjectMapper json;
    private final Redactor redactor;
    public DiscoveryGateway(ConsoleSettings settings,ObjectMapper json,Redactor redactor){this.settings=settings;this.json=json;this.redactor=redactor;}
    public static String origin(ConsoleSettings.Connection connection) {
        try {return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(
                (connection.apiUrl()+"\n"+connection.namespace()).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("连接身份不可计算");}
    }
    private RestClient client(ConsoleSettings.Connection connection) {
        return client(connection,Duration.ofSeconds(5));
    }
    private RestClient client(ConsoleSettings.Connection connection,Duration timeout) {
        var builder=HttpClient.newBuilder().connectTimeout(timeout).followRedirects(HttpClient.Redirect.NEVER);
        String ca=settings.connectionCaCertificate(connection);if(!ca.isBlank())builder.sslContext(ConnectionTls.context(ca));
        var factory=new JdkClientHttpRequestFactory(builder.build());factory.setReadTimeout(timeout);
        var result=RestClient.builder().requestFactory(factory);
        String token=settings.connectionToken(connection);if(!token.isBlank())result.defaultHeader("Authorization","Bearer "+token);
        return result.build();
    }
    private String path(ConsoleSettings.Connection connection,Kind kind) {
        return connection.apiUrl()+"/apis/platform.lanhao.io/v1alpha1/namespaces/"+connection.namespace()+"/"+kind.plural;
    }
    public List<Observation> scan(ConsoleSettings.Connection connection) {
        return scan(connection,List.of(connection.namespace()));
    }
    public List<Observation> scan(ConsoleSettings.Connection base,List<String> namespaces) {
        DiscoveryStore.validateNamespaces(namespaces);
        RestClient client=client(base,Duration.ofSeconds(2));long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        List<Observation> result=new ArrayList<>();int requests=0;
        for(String namespace:namespaces)for(Kind kind:Kind.values()) {
            var connection=DiscoveryStore.scoped(base,namespace);
            String continuation="";Set<String> seen=new HashSet<>();
            do {
                if(++requests>100||System.nanoTime()>deadline)throw new ConsoleFailure("部署清单超过单次同步预算，请缩小连接的命名空间范围");
                String url=path(connection,kind)+"?limit=100"+(continuation.isBlank()?"":"&continue="+URLEncoder.encode(continuation,StandardCharsets.UTF_8));
                JsonNode page=get(client,url);
                if(!page.path("items").isArray())throw new ConsoleFailure("网关未返回 Operator CR 列表，请核对代理路径和 CRD");
                for(JsonNode resource:page.path("items")) {
                    if(result.size()>=2000)throw new ConsoleFailure("部署清单超过 2000 条，本轮未更新任务");
                    result.add(observation(connection,kind,resource));
                }
                continuation=page.path("metadata").path("continue").asText("");
                if(continuation.length()>4096||!continuation.isBlank()&&!seen.add(continuation))throw new ConsoleFailure("部署清单分页异常，本轮未更新任务");
            }while(!continuation.isBlank());
        }
        return List.copyOf(result);
    }
    public Observation read(ConsoleSettings.Connection connection,Target expected) {
        return read(connection,expected,Duration.ofSeconds(5));
    }
    public Observation readForChat(ConsoleSettings.Connection connection,Target expected) {
        return read(connection,expected,Duration.ofSeconds(2));
    }
    private Observation read(ConsoleSettings.Connection connection,Target expected,Duration timeout) {
        Observation current=observation(connection,expected.kind(),get(client(connection,timeout),path(connection,expected.kind())+"/"+expected.name()));
        if(!current.target().equals(expected))throw new ConsoleFailure("资源身份或代次已变化，请同步并选择最新部署任务");
        return current;
    }
    private JsonNode get(RestClient client,String url) {
        try {
            return client.get().uri(URI.create(url)).exchange((request,response)->{
                int code=response.getStatusCode().value();
                if(code<200||code>=300)throw new ConsoleFailure("读取部署失败（HTTP "+code+"），请检查 CRD、连接及目标命名空间的 CR 只读权限；不跟随重定向");
                byte[] bytes=response.getBody().readNBytes(2_097_153);
                if(bytes.length>2_097_152)throw new ConsoleFailure("部署清单响应超过 2 MiB，本轮未更新任务");
                JsonNode value=json.readTree(bytes);if(value==null||!value.isObject())throw new ConsoleFailure("网关部署响应格式无效");
                return value;
            });
        }catch(ConsoleFailure failure){throw failure;}
        catch(Exception failure){throw new ConsoleFailure("部署读取失败，请检查网络、证书、连接地址和 CR 只读权限");}
    }
    private Observation observation(ConsoleSettings.Connection connection,Kind kind,JsonNode resource) {
        JsonNode meta=resource.path("metadata"),status=resource.path("status"),labels=meta.path("labels");
        String name=meta.path("name").asText(""),namespace=meta.path("namespace").asText(""),uid=meta.path("uid").asText("");
        String token=settings.connectionToken(connection);
        long generation=meta.path("generation").asLong(0);
        if(!namespace.equals(connection.namespace())||!name.matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?")||name.length()>253
                ||!uid.matches("[A-Za-z0-9_-]{1,100}")||generation<1||!resource.path("kind").asText("").equals(kind.name())
                ||!resource.path("apiVersion").asText("").equals("platform.lanhao.io/v1alpha1")
                ||!token.isBlank()&&(name.contains(token)||namespace.contains(token)||uid.contains(token)))throw new ConsoleFailure("部署资源身份无效，本轮未更新任务");
        String phase=meta.hasNonNull("deletionTimestamp")?"Deleting":safe(status.path("phase").asText("Pending"),80,token);
        return new Observation(new Target(kind,namespace,name,uid,generation),safe(resource.path("spec").path("releaseVersion").asText(""),100,token),
                safe(labels.path("platform.lanhao.io/project-id").asText(""),100,token),safe(labels.path("platform.lanhao.io/environment").asText(""),80,token),
                phase,status.path("observedGeneration").asLong(-1),safe(status.path("reason").asText(""),300,token),safe(status.path("message").asText(""),2000,token));
    }
    private String safe(String value,int limit,String token){String text=redactor.clean(token.isBlank()?value:value.replace(token,"[已脱敏]"));return text.substring(0,Math.min(text.length(),limit));}
}
