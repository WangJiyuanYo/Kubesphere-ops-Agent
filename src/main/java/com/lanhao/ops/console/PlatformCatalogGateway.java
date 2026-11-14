package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.AgentProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** 只读取平台授权后的目录，不能以数据库连接代替平台权限。 */
@Component
public class PlatformCatalogGateway {
    static class AuthorizationFailure extends ConsoleFailure {
        AuthorizationFailure(){super("平台目录授权失败，请检查平台凭据和数据范围");}
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public record Entry(String id,String siteId,String version,String status,String currentStep,String workspaceName,
            String projectName,String domain,String projectId,String namespace,String clusterCode,String clusterApiUrl,String unavailableReason) {}
    public record Batch(List<Entry> items,boolean partial) {}
    private final PlatformCatalogProperties options;
    private final ObjectMapper json;
    private final RestClient client;
    private final String source;
    @org.springframework.beans.factory.annotation.Autowired
    public PlatformCatalogGateway(AgentProperties config,PlatformCatalogProperties options,RestClient.Builder builder,ObjectMapper json) {
        this.options=options;this.json=json;
        if(!options.enabled()){client=null;source="disabled";return;}
        if(!options.accepts(config.platformBaseUrl())
                ||config.platformToken()==null||config.platformToken().isBlank())throw new IllegalArgumentException("平台目录需要 HTTPS 地址（本机回环 HTTP 须显式启用）及受权凭据");
        try {source=HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest((config.platformBaseUrl()+"\n"+config.platformToken()).getBytes(StandardCharsets.UTF_8)));}
        catch(Exception failure){throw new IllegalStateException("目录来源不可计算");}
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.clone().baseUrl(config.platformBaseUrl().toString()).defaultHeader("Authorization","Bearer "+config.platformToken())
                .defaultHeader("Accept","application/json").requestFactory(factory).build();
    }
    public boolean enabled(){return options.enabled();}
    PlatformCatalogGateway(PlatformCatalogProperties options,ObjectMapper json,RestClient client,String source) {
        this.options=options;this.json=json;this.client=client;this.source=source;
    }
    public String source(){return source;}
    public Batch scan() {
        if(!enabled())return new Batch(List.of(),false);
        List<Entry> entries=new ArrayList<>();Set<String> ids=new HashSet<>();boolean more=false;
        long deadline=System.nanoTime()+Duration.ofSeconds(40).toNanos();
        for(int page=1;page<=options.maxPages();page++) {
            if(System.nanoTime()>=deadline)throw new ConsoleFailure("平台目录同步超时，请减少扫描页数后重试");
            JsonNode data=get("/provisioning-instances?page="+page);
            if(!data.path("items").isArray()||data.path("items").size()>100||!data.path("hasMore").isBoolean())throw new ConsoleFailure("平台目录响应不兼容");
            for(JsonNode item:data.path("items")) {
                Entry entry=entry(item);if(!ids.add(entry.id()))throw new ConsoleFailure("平台目录分页结果重复，本轮未采纳");entries.add(entry);
            }
            more=data.path("hasMore").asBoolean();if(!more)break;
        }
        return new Batch(List.copyOf(entries),more);
    }
    public Entry current(String id) {
        if(!enabled()||id==null||!id.matches("[1-9][0-9]{0,18}"))throw new ConsoleFailure("平台目录未启用或任务身份无效");
        var data=get("/provisioning-instances/"+id+"/catalog-entry");
        if(!id.equals(data.path("id").asText()))throw new ConsoleFailure("平台任务身份不匹配");
        return entry(data);
    }
    private JsonNode get(String path) {
        try {
            return client.get().uri(path).accept(org.springframework.http.MediaType.APPLICATION_JSON).exchange((request,response)->{
                int status=response.getStatusCode().value();
                if(status==401||status==403)throw new AuthorizationFailure();
                if(status!=200)throw new ConsoleFailure("平台目录暂不可读取（HTTP "+status+"）");
                byte[] body=response.getBody().readNBytes(2*1024*1024+1);
                if(body.length>2*1024*1024)throw new ConsoleFailure("平台目录响应超过读取上限");
                var root=json.readTree(body);
                if(root==null||root.path("code").asInt()!=200||!root.path("data").isObject())throw new ConsoleFailure("平台目录业务响应不兼容");
                return root.path("data");
            });
        }catch(ConsoleFailure safe){throw safe;}
        catch(RuntimeException failure){throw new ConsoleFailure("平台目录请求失败，请检查平台配置、网络和服务状态");}
    }
    private Entry entry(JsonNode node) {
        Entry result=json.convertValue(node,Entry.class);
        if(result.id()==null||!result.id().matches("[1-9][0-9]{0,18}")||result.siteId()==null||!result.siteId().matches("[1-9][0-9]{0,18}")
                ||result.version()==null||result.version().isBlank()||result.version().length()>100)throw new ConsoleFailure("平台目录缺少任务身份");
        return result;
    }
}
