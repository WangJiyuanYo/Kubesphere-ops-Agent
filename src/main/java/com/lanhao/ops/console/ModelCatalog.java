package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import java.net.URI;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.*;

/** 读取网关模型目录，不保存配置、不发起推理、不信任上游错误正文。 */
@Component
public class ModelCatalog {
    public record Result(List<String> models,boolean truncated) {}
    private final ConsoleSettings settings;
    private final ObjectMapper json;
    private final RestClient client;
    public ModelCatalog(ConsoleSettings settings,ObjectMapper json) {
        this.settings=settings;this.json=json;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(30));client=RestClient.builder().requestFactory(factory).build();
    }
    public Result list(ConsoleSettings.ModelCatalogInput input) {
        String url=settings.validateUrl(input.baseUrl());
        String key=settings.modelCatalogKey(input,url);
        String path=input.provider()==ConsoleSettings.Provider.DASHSCOPE?"/compatible-mode/v1/models":"/models";
        try {
            return client.get().uri(URI.create(url+path)).headers(headers->headers.setBearerAuth(key))
                    .exchange((request,response)->{
                        int status=response.getStatusCode().value();
                        if(status<200||status>=300)throw new ConsoleFailure(error(status));
                        byte[] bytes=response.getBody().readNBytes(1_048_577);
                        if(bytes.length>1_048_576)throw new ConsoleFailure("模型列表响应过大，请手工填写模型名称");
                        JsonNode root=json.readTree(bytes),data=root==null?null:root.get("data");
                        if(data==null||!data.isArray())throw new ConsoleFailure("服务未返回标准模型列表，请核对协议与地址，或手工填写模型名称");
                        SortedSet<String> ids=new TreeSet<>();
                        for(JsonNode item:data){
                            JsonNode value=item.get("id");if(value==null||!value.isTextual())continue;
                            String id=value.asText().trim();
                            if(id.matches("[A-Za-z0-9][A-Za-z0-9._:/@+\\-]{0,99}")&&!id.contains(key)&&!id.startsWith("sk-"))ids.add(id);
                        }
                        List<String> models=new ArrayList<>(ids);
                        return new Result(List.copyOf(models.subList(0,Math.min(models.size(),500))),models.size()>500);
                    });
        }catch(ConsoleFailure e){throw e;}
        catch(Exception e){throw new ConsoleFailure("获取模型列表失败，请检查网络、地址、API Key 和服务响应；也可手工填写模型名称");}
    }
    private String error(int status) {
        String detail=switch(status){
            case 401,403->"请检查 API Key 及模型目录访问权限";
            case 404,405->"服务未提供模型列表接口，请核对 Base URL，或手工填写模型名称";
            case 429->"请求过于频繁，请稍后重试";
            default->status>=300&&status<400?"服务返回重定向，请直接填写最终 API 地址":"模型服务暂时不可用，请稍后重试";
        };
        return "获取模型列表失败（HTTP "+status+"）："+detail;
    }
}
