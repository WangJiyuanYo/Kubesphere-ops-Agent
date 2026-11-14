package com.lanhao.ops.console;

import com.alibaba.cloud.ai.dashscope.api.DashScopeApi;
import com.alibaba.cloud.ai.dashscope.chat.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.converter.BeanOutputConverter;
import org.springframework.ai.openai.*;
import org.springframework.ai.openai.api.OpenAiApi;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.retry.support.RetryTemplate;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.ResponseErrorHandler;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.UnknownContentTypeException;
import org.springframework.http.converter.HttpMessageConversionException;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.http.HttpMethod;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.net.URI;
import java.net.ConnectException;
import java.net.UnknownHostException;
import java.net.SocketTimeoutException;
import java.net.http.HttpTimeoutException;
import javax.net.ssl.SSLException;
import java.net.http.HttpClient;
import java.time.Duration;
import java.util.List;

@Component
public class ModelGateway {
    private static final Logger log=LoggerFactory.getLogger(ModelGateway.class);
    private final ConsoleSettings settings;
    private final RestClient.Builder rest;
    private volatile Cached cached;
    private record Cached(ConsoleSettings.Llm settings, ChatModel model) {}
    public ModelGateway(ConsoleSettings settings,ConsoleProperties config) {
        this.settings=settings;
        var factory=new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(config.modelReadTimeout());
        rest=RestClient.builder().requestFactory(factory);
    }
    public synchronized ChatModel model(ConsoleSettings.Llm llm) {
        if(cached!=null&&cached.settings().equals(llm)) return cached.model();
        String key=settings.llmKey(llm);
        if(key.isBlank()) throw new ConsoleFailure("请先配置模型 API Key");
        RetryTemplate retry=RetryTemplate.builder().maxAttempts(1).fixedBackoff(1).build();
        ChatModel model;
        if(llm.provider()==ConsoleSettings.Provider.DASHSCOPE) {
            var options=new DashScopeChatOptions();
            options.setModel(llm.model()); options.setTemperature(llm.temperature()); options.setMaxTokens(llm.maxTokens());
            var api=DashScopeApi.builder().baseUrl(llm.baseUrl()).apiKey(key).restClientBuilder(rest.clone()).responseErrorHandler(errors()).build();
            model=DashScopeChatModel.builder().dashScopeApi(api).defaultOptions(options).retryTemplate(retry).build();
        } else {
            // 页面 Base URL 包含 /v1 或 /compatible-mode/v1，SDK 仅追加 /chat/completions。
            var api=OpenAiApi.builder().baseUrl(llm.baseUrl()).completionsPath("/chat/completions")
                    .apiKey(key).restClientBuilder(rest.clone()).responseErrorHandler(errors()).build();
            var options=OpenAiChatOptions.builder().model(llm.model()).temperature(llm.temperature()).maxTokens(llm.maxTokens()).build();
            model=OpenAiChatModel.builder().openAiApi(api).defaultOptions(options).retryTemplate(retry).build();
        }
        cached=new Cached(llm,model);
        return model;
    }
    public String complete(ConsoleSettings.Llm llm, List<Message> messages) {
        try{return invoke(model(llm),messages);}
        catch(RuntimeException failure){throw classify(failure);}
    }
    public <T> T structured(ConsoleSettings.Llm llm,List<Message> messages,Class<T> type) {
        var converter=new BeanOutputConverter<>(type);
        var input=new java.util.ArrayList<Message>(messages);input.add(new SystemMessage(converter.getFormat()));
        return convert(complete(llm,input),converter);
    }
    public static <T> T structured(ChatModel model,List<Message> messages,Class<T> type) {
        var converter=new BeanOutputConverter<>(type);
        var input=new java.util.ArrayList<Message>(messages);input.add(new SystemMessage(converter.getFormat()));
        return convert(invoke(model,input),converter);
    }
    private static <T> T convert(String text,BeanOutputConverter<T> converter) {
        try { T value=converter.convert(text);if(value==null)throw new IllegalArgumentException();return value; }
        catch(RuntimeException failure){log.warn("Model output invalid: code=INVALID_RESPONSE");throw new ModelFailure(ModelFailure.Code.INVALID_RESPONSE,0);}
    }
    private static String invoke(ChatModel model,List<Message> messages) {
        long started=System.nanoTime();
        try {
            var response=model.call(new Prompt(messages));
            String text=response==null||response.getResult()==null||response.getResult().getOutput()==null?null:response.getResult().getOutput().getText();
            if(text==null||text.isBlank()) throw new ModelFailure(ModelFailure.Code.EMPTY_RESPONSE,0);
            return text.substring(0,Math.min(text.length(),8000));
        } catch(RuntimeException failure) {
            ModelFailure safe=classify(failure);
            // 不记录原异常：SDK 异常常含上游错误正文，可能夹带凭据或输入。
            log.warn("Model request failed: code={}, httpStatus={}, elapsedMs={}",safe.code(),safe.status(),(System.nanoTime()-started)/1_000_000);
            throw safe;
        }
    }
    private static ResponseErrorHandler errors() {
        return new ResponseErrorHandler() {
            public boolean hasError(ClientHttpResponse response)throws IOException{return response.getStatusCode().value()>=300;}
            public void handleError(URI url,HttpMethod method,ClientHttpResponse response)throws IOException {
                int status=response.getStatusCode().value();String code="";
                byte[] body=response.getBody().readNBytes(16_385);
                if(body.length<=16_384)try {
                    var value=new ObjectMapper().readTree(body);
                    if(value!=null)code=value.path("error").path("code").asText(value.path("code").asText(""));
                }catch(IOException ignored){/* 非 JSON 错误仅按 HTTP 状态分类。 */}
                throw httpFailure(status,code);
            }
        };
    }
    private static ModelFailure httpFailure(int status,String code) {
        var kind=switch(status) {
            case 401->ModelFailure.Code.AUTHENTICATION;
            case 403->ModelFailure.Code.PERMISSION;
            case 404->ModelFailure.Code.ENDPOINT_NOT_FOUND;
            case 408,504->ModelFailure.Code.TIMEOUT;
            case 429->ModelFailure.Code.RATE_LIMIT;
            default->status>=500?ModelFailure.Code.UPSTREAM_UNAVAILABLE:
                    status>=300&&status<400?ModelFailure.Code.REDIRECT:ModelFailure.Code.INVALID_REQUEST;
        };
        if((status==400||status==404)&&"model_not_found".equals(code))kind=ModelFailure.Code.MODEL_UNAVAILABLE;
        if((status==400||status==402||status==429)&&"insufficient_quota".equals(code))kind=ModelFailure.Code.RATE_LIMIT;
        return new ModelFailure(kind,status);
    }
    public static ModelFailure classify(Throwable failure) {
        var seen=java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<Throwable,Boolean>());
        boolean network=false,format=false;
        for(Throwable cause=failure;cause!=null&&seen.add(cause);cause=cause.getCause()) {
            if(cause instanceof ModelFailure safe)return safe;
            if(cause instanceof ConsoleFailure)return new ModelFailure(ModelFailure.Code.CONFIGURATION,0);
            if(cause instanceof HttpTimeoutException||cause instanceof SocketTimeoutException)return new ModelFailure(ModelFailure.Code.TIMEOUT,0);
            if(cause instanceof RestClientResponseException response)return httpFailure(response.getStatusCode().value(),"");
            network|=cause instanceof ResourceAccessException||cause instanceof ConnectException||cause instanceof UnknownHostException||cause instanceof SSLException;
            format|=cause instanceof HttpMessageConversionException||cause instanceof UnknownContentTypeException;
        }
        return new ModelFailure(network?ModelFailure.Code.CONNECTION:format?ModelFailure.Code.INVALID_RESPONSE:ModelFailure.Code.UNKNOWN,0);
    }
}
