package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.JsonNode;
import com.lanhao.ops.evidence.Redactor;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.*;
import java.util.*;

@Component
public class KubeSphereGateway {
    public record PodSummary(String name,String phase,boolean ready) {}
    public record Snapshot(boolean connected,String namespace,List<PodSummary> pods,String summary,Instant checkedAt) {}
    private final ConsoleSettings settings;
    private final RestClient client;
    private final Redactor redactor;
    private static RestClient client(String ca) {
        var builder=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER);
        if(ca!=null&&!ca.isBlank())builder.sslContext(ConnectionTls.context(ca));
        var factory=new JdkClientHttpRequestFactory(builder.build());factory.setReadTimeout(Duration.ofSeconds(10));
        return RestClient.builder().requestFactory(factory).build();
    }
    public KubeSphereGateway(ConsoleSettings settings,Redactor redactor) {
        this.settings=settings; this.redactor=redactor;
        client=client(null);
    }
    public Snapshot inspect(ConsoleSettings.Connection connection) {
        try {
            String ca=settings.connectionCaCertificate(connection);
            var request=(ca.isBlank()?client:client(ca)).get().uri(URI.create(connection.apiUrl()+"/api/v1/namespaces/"+connection.namespace()+"/pods?limit=10"));
            String token=settings.connectionToken(connection);
            if(!token.isBlank()) request.header("Authorization","Bearer "+token);
            JsonNode result=request.exchange((req,response)->{
                int status=response.getStatusCode().value();
                if(status<200||status>=300)throw new ConsoleFailure("Pod 连接测试失败（HTTP "+status+"）："
                        +(status==403?"当前连接缺少命名空间 "+connection.namespace()+" 的 pods list 权限；日志还需要 pods/log get，事件需要 events list"
                        :status==401?"Token 无效或已过期":status>=300&&status<400?"代理发生重定向，未转发凭据":"请核对 API 地址、代理路径及服务状态"));
                byte[] bytes=response.getBody().readNBytes(1_048_577);
                if(bytes.length>1_048_576)throw new ConsoleFailure("Pod 测试响应超过 1 MiB，未采纳结果");
                return new com.fasterxml.jackson.databind.ObjectMapper().readTree(bytes);
            });
            if(result==null||!result.path("items").isArray()) throw new ConsoleFailure("网关返回内容不是 Pod 列表，请核对 Kubernetes 代理地址");
            List<PodSummary> pods=new ArrayList<>();
            for(JsonNode pod:result.path("items")) {
                if(pods.size()==10) break;
                boolean ready=false;
                for(JsonNode condition:pod.path("status").path("conditions"))
                    if("Ready".equals(condition.path("type").asText())&&"True".equals(condition.path("status").asText())) ready=true;
                pods.add(new PodSummary(redactor.clean(pod.path("metadata").path("name").asText()),
                        redactor.clean(pod.path("status").path("phase").asText()),ready));
            }
            return new Snapshot(true,connection.namespace(),List.copyOf(pods),"连接成功，已验证目标命名空间的 Pod 只读权限（最多展示 10 个）",Instant.now());
        } catch(ConsoleFailure e){throw e;}
        catch(RuntimeException e){throw new ConsoleFailure("连接失败，请检查 KubeSphere API 地址、Token、命名空间和只读权限");}
    }
}
