package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.AgentProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;
import static com.lanhao.ops.console.ProvisioningModel.*;

/** 提议的 v1 平台阶段只读契约；默认关闭，不把旧目录猜测为阶段流。 */
@Component
public class ProvisioningGateway {
    public record Page(List<StageEvent> items,String nextCursor,boolean hasMore) {}
    public static class CursorExpired extends ConsoleFailure { public CursorExpired(){super("阶段游标过期，将从有界首页重新同步");} }
    private final ProvisioningProperties options;
    private final ObjectMapper json;
    private final RestClient client;
    private final String authorizationHash;
    public ProvisioningGateway(AgentProperties config,ProvisioningProperties options,PlatformCatalogProperties catalog,RestClient.Builder builder,ObjectMapper json) {
        this.options=options;this.json=json;
        if(!options.enabled()){client=null;authorizationHash="";return;}
        if(!catalog.accepts(config.platformBaseUrl())||ProvisioningStore.blank(config.platformToken()))throw new IllegalArgumentException("阶段协议需要受权平台 HTTPS 地址及凭据");
        authorizationHash=ProvisioningStore.hash(config.platformBaseUrl()+"\n"+config.platformToken());
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client=builder.clone().baseUrl(config.platformBaseUrl().toString()).defaultHeader("Authorization","Bearer "+config.platformToken()).requestFactory(factory).build();
    }
    public boolean enabled(){return options.enabled();}
    public String authorizationHash(){return authorizationHash;}
    public Page page(String cursor) {
        JsonNode data=get("/provisioning-stage-events",Map.of("cursor",cursor));
        if(!data.path("items").isArray()||data.path("items").size()>100||!data.path("hasMore").isBoolean()
                ||!data.path("nextCursor").isTextual()||data.path("nextCursor").asText().length()>1000)throw new ConsoleFailure("阶段分页契约不兼容");
        var items=new ArrayList<StageEvent>();for(var item:data.path("items"))items.add(json.convertValue(item,StageEvent.class));
        boolean more=data.path("hasMore").asBoolean();String next=data.path("nextCursor").asText();
        if(more&&(next.isBlank()||next.equals(cursor)))throw new ConsoleFailure("阶段分页游标没有推进");
        return new Page(List.copyOf(items),next,more);
    }
    public StageEvent current(Watch watch) {
        var result=json.convertValue(get("/provisioning-instances/"+watch.stage().provisioningId()+"/stage",Map.of("deploymentVersion",watch.stage().deploymentVersion())),StageEvent.class);
        if(!result.platformInstanceId().equals(watch.stage().platformInstanceId())||!result.provisioningId().equals(watch.stage().provisioningId())
                ||!result.deploymentVersion().equals(watch.stage().deploymentVersion())||!result.workspaceId().equals(watch.stage().workspaceId())||!result.siteId().equals(watch.stage().siteId()))
            throw new ContextMismatch("平台当前阶段已属于其他站点或发布，停止旧范围自动托管");
        return result;
    }
    private JsonNode get(String path,Map<String,String> query) {
        if(!enabled())throw new ConsoleFailure("阶段协议尚未启用");
        try {
            return client.get().uri(builder->{builder.path(path);query.forEach((key,value)->builder.queryParam(key,"{"+key+"}"));return builder.build(query);})
                    .accept(org.springframework.http.MediaType.APPLICATION_JSON).exchange((request,response)->{
                        int status=response.getStatusCode().value();
                        if(status==410)throw new CursorExpired();
                        if(status==401||status==403)throw new ConsoleFailure("平台阶段授权失败（HTTP "+status+"），观察已降级");
                        if(status!=200)throw new ConsoleFailure("平台阶段暂不可读取（HTTP "+status+"），观察已降级");
                        byte[] body=response.getBody().readNBytes(2*1024*1024+1);
                        if(body.length>2*1024*1024)throw new ConsoleFailure("阶段响应超过上限");
                        var root=json.readTree(body);
                        if(root==null||!root.path("code").isIntegralNumber()||root.path("code").asInt()!=200||!root.path("data").isObject())throw new ConsoleFailure("平台阶段业务响应不兼容");
                        return root.path("data");
                    });
        }catch(ConsoleFailure safe){throw safe;}catch(RuntimeException failure){throw new ConsoleFailure("阶段请求失败，观察已降级，请检查网络与阶段契约");}
    }
}
