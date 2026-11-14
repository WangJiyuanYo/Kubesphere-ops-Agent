package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.evidence.Redactor;
import com.lanhao.ops.workflow.RunScheduler;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.springframework.ai.chat.messages.UserMessage;
import static org.assertj.core.api.Assertions.*;

@EnabledIfEnvironmentVariable(named="TEST_DATABASE_URL",matches="jdbc:postgresql:.*")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT,properties={
        "agent.memory.file=./tmp/console-test-memory.db",
        "spring.datasource.url=${TEST_DATABASE_URL}",
        "spring.datasource.username=${TEST_DATABASE_USER:deployment_agent}",
        "spring.datasource.password=${TEST_DATABASE_PASSWORD:demo-postgres-only}",
        "agent.console.allow-http=true",
        "agent.console.encryption-key=AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA="})
class ConsoleIntegrationTest {
    @Autowired ConsoleSettings settings;
    @Autowired ChatStore chats;
    @Autowired AgentChat agent;
    @Autowired ModelGateway models;
    @Autowired ModelCatalog catalog;
    @Autowired RuntimeNarrator narrator;
    @Autowired KubeSphereGateway clusters;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired TestRestTemplate http;
    @MockitoBean RunScheduler scheduler;
    @MockitoBean com.lanhao.ops.memory.LearningOutbox backgroundLearning;
    HttpServer server;
    String base;
    AtomicReference<String> path=new AtomicReference<>(), authorization=new AtomicReference<>(), body=new AtomicReference<>();
    AtomicReference<String> reply=new AtomicReference<>();
    AtomicInteger status=new AtomicInteger(200), calls=new AtomicInteger();

    @BeforeEach void setup() throws Exception {
        jdbc.execute("TRUNCATE agent_run,agent_console_llm,agent_console_connection,agent_chat_session CASCADE");
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",exchange->{
            calls.incrementAndGet(); path.set(exchange.getRequestURI().toString());
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));
            body.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(status.get()==302) exchange.getResponseHeaders().set("Location",base+"/redirected");
            byte[] bytes=Objects.toString(reply.get(),"{}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try(var output=exchange.getResponseBody()){output.write(bytes);}
        });
        server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void markdownAssetsLoadBeforeLoginWithoutOpeningConsoleApis() {
        for (String path : List.of("/markdown.js", "/vendor/marked.umd.js", "/vendor/purify.min.js")) {
            var response = http.getForEntity(path, String.class);
            assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
            assertThat(response.getHeaders().getContentType().toString()).contains("javascript");
            assertThat(response.getBody()).isNotBlank();
            assertThat(response.getHeaders().getFirst("Content-Security-Policy")).contains("script-src 'self'");
        }
        assertThat(http.getForEntity("/api/console/bootstrap", String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
    ConsoleSettings.LlmInput llm(long revision,String url,String key,boolean enabled,boolean clear) {
        return new ConsoleSettings.LlmInput(revision,enabled,ConsoleSettings.Provider.OPENAI_COMPATIBLE,url,"test-model",key,clear,0.1,512);
    }
    ConsoleSettings.ConnectionInput connection(long revision,String url,String token,boolean clear) {
        return new ConsoleSettings.ConnectionInput(revision,"测试环境",url,"https://console.example.com","agent-demo",token,clear);
    }
    String storedLlm(){return jdbc.queryForObject("SELECT payload::text FROM agent_console_llm",String.class);}
    ConsoleSettings.ModelCatalogInput catalogInput(long revision,String url,String key) {
        return new ConsoleSettings.ModelCatalogInput(revision,ConsoleSettings.Provider.OPENAI_COMPATIBLE,url,key,false);
    }
    @Test void modelDirectoryUsesSavedKeyAndConfiguredPrefixWithoutMutatingSettings() {
        settings.saveLlm(llm(0,base+"/compatible-mode/v1","fake-api-key",false,false));String before=storedLlm();
        reply.set("{\"data\":[{\"id\":\"z-model\"},{\"id\":\"a-model\"},{\"id\":\"a-model\"},{\"id\":\"fake-api-key\"},{\"id\":\"<script>\"},{\"id\":123}]}");
        HttpHeaders headers=new HttpHeaders();headers.set("X-Console-Request","1");
        var response=http.withBasicAuth("operator","demo-local-only").postForEntity("/api/console/llm/models",new HttpEntity<>(catalogInput(1,base+"/compatible-mode/v1/",""),headers),JsonNode.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody().path("models").toString()).isEqualTo("[\"a-model\",\"z-model\"]");
        assertThat(response.getBody().path("truncated").asBoolean()).isFalse();
        assertThat(response.getBody().toString()).doesNotContain("fake-api-key","encryptedKey");
        assertThat(path.get()).isEqualTo("/compatible-mode/v1/models");assertThat(authorization.get()).isEqualTo("Bearer fake-api-key");
        assertThat(storedLlm()).isEqualTo(before);
    }
    @Test void modelDirectoryAllowsFirstSetupButNeverReusesSavedKeyForChangedDestination() {
        reply.set("{\"data\":[{\"id\":\"first-model\"}]}");
        assertThat(catalog.list(catalogInput(0,base+"/v1","unsaved-key")).models()).containsExactly("first-model");
        assertThat(settings.llm()).isEmpty();
        settings.saveLlm(llm(0,base+"/v1","saved-key",false,false));int before=calls.get();
        assertThatThrownBy(()->catalog.list(catalogInput(1,base+"/other",""))).isInstanceOf(ConsoleFailure.class).hasMessageContaining("重新填写");
        assertThatThrownBy(()->catalog.list(new ConsoleSettings.ModelCatalogInput(1,ConsoleSettings.Provider.DASHSCOPE,base+"/v1","",false))).isInstanceOf(ConsoleFailure.class);
        assertThatThrownBy(()->catalog.list(catalogInput(0,base+"/v1",""))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->catalog.list(new ConsoleSettings.ModelCatalogInput(1,ConsoleSettings.Provider.OPENAI_COMPATIBLE,base+"/v1","new-key",true))).isInstanceOf(ConsoleFailure.class);
        assertThat(calls.get()).isEqualTo(before);
        assertThat(catalog.list(catalogInput(1,base+"/other","explicit-key")).models()).containsExactly("first-model");
        assertThat(authorization.get()).isEqualTo("Bearer explicit-key");
        assertThat(settings.llm().orElseThrow().baseUrl()).isEqualTo(base+"/v1");
        assertThat(settings.llmKey(settings.llm().orElseThrow())).isEqualTo("saved-key");
    }
    @Test void modelDirectoryErrorsAreSafeAndRedirectsAreNeverFollowed() {
        var input=catalogInput(0,base+"/v1","fake-secret-key");
        reply.set("{\"error\":\"fake-secret-key\"}");
        for(int code:List.of(302,401,403,404,405,429,503)) {
            status.set(code);int before=calls.get();
            assertThatThrownBy(()->catalog.list(input)).isInstanceOf(ConsoleFailure.class).hasMessageContaining("HTTP "+code).hasMessageNotContaining("fake-secret-key");
            assertThat(calls.get()).isEqualTo(before+1);
        }
        status.set(200);
        for(String malformed:List.of("{}","{\"data\":{}}","not-json","null")) {
            reply.set(malformed);assertThatThrownBy(()->catalog.list(input)).isInstanceOf(ConsoleFailure.class).hasMessageNotContaining("fake-secret-key");
        }
        reply.set("{\"data\":[]}");assertThat(catalog.list(input).models()).isEmpty();
    }
    @Test void modelDirectoryBoundsResponsesAndReturnsOnlySelectableIds() throws Exception {
        var input=catalogInput(0,base+"/v1","key-for-test");
        var items=new ArrayList<Map<String,String>>();
        for(int i=0;i<501;i++)items.add(Map.of("id",String.format("model-%03d",i)));
        items.add(Map.of("id","a".repeat(101)));items.add(Map.of("id","sk-hidden"));
        reply.set(json.writeValueAsString(Map.of("data",items)));
        var result=catalog.list(input);assertThat(result.models()).hasSize(500);assertThat(result.truncated()).isTrue();
        assertThat(result.models()).doesNotContain("sk-hidden","a".repeat(101));
        reply.set(" ".repeat(1_048_577));assertThatThrownBy(()->catalog.list(input)).isInstanceOf(ConsoleFailure.class).hasMessageContaining("响应过大");
    }
    @Test void nativeDashscopeDirectoryKeepsCredentialOnSameBaseAddress() {
        reply.set("{\"data\":[{\"id\":\"qwen-plus\"}]}");
        assertThat(catalog.list(new ConsoleSettings.ModelCatalogInput(0,ConsoleSettings.Provider.DASHSCOPE,base,"native-fake-key",false)).models()).containsExactly("qwen-plus");
        assertThat(path.get()).isEqualTo("/compatible-mode/v1/models");assertThat(authorization.get()).isEqualTo("Bearer native-fake-key");
    }
    @Test void modelDirectoryApiRequiresAuthenticationOriginHeaderAndValidInput() {
        var input=catalogInput(0,base+"/v1","unsaved-key");
        HttpHeaders headers=new HttpHeaders();headers.set("X-Console-Request","1");
        assertThat(http.postForEntity("/api/console/llm/models",new HttpEntity<>(input,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var auth=http.withBasicAuth("operator","demo-local-only");
        assertThat(auth.postForEntity("/api/console/llm/models",input,String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        headers.setOrigin("https://evil.example");
        assertThat(auth.postForEntity("/api/console/llm/models",new HttpEntity<>(input,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        headers.remove("Origin");
        assertThat(auth.postForEntity("/api/console/llm/models",new HttpEntity<>(Map.of("baseUrl",base),headers),String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(calls.get()).isZero();
    }
    @Test void settingsPersistEncryptedWithoutCredentialEchoAndRequireRevision() throws Exception {
        var saved=settings.saveLlm(llm(0,base+"/v1","fake-api-key",true,false));
        assertThat(saved.hasApiKey()).isTrue();
        assertThat(json.writeValueAsString(saved)).doesNotContain("fake-api-key","encryptedKey");
        assertThat(storedLlm()).doesNotContain("fake-api-key").contains("v1:");
        settings.saveLlm(llm(1,base+"/v1","",true,false));
        assertThat(settings.llmKey(settings.llm().orElseThrow())).isEqualTo("fake-api-key");
        assertThatThrownBy(()->settings.saveLlm(llm(1,base+"/v1","",true,false))).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(()->settings.saveLlm(llm(2,base+"/other","",true,false))).isInstanceOf(IllegalArgumentException.class);
        assertThat(settings.saveLlm(llm(2,base+"/v1","",false,true)).hasApiKey()).isFalse();
    }
    @Test void connectionCredentialsStayWithTheirGatewayAndDeletionDetachesConversation() {
        UUID id=UUID.randomUUID();
        settings.saveConnection(id,connection(0,base,"fake-cluster-token",false));
        assertThat(jdbc.queryForObject("SELECT payload::text FROM agent_console_connection",String.class)).doesNotContain("fake-cluster-token");
        settings.saveConnection(id,connection(1,base,"",false));
        assertThat(settings.connectionToken(settings.connection(id))).isEqualTo("fake-cluster-token");
        assertThatThrownBy(()->settings.saveConnection(id,connection(2,base+"/other","",false))).isInstanceOf(IllegalArgumentException.class);
        var session=chats.create("测试",id,null);
        assertThatThrownBy(()->settings.deleteConnection(id,1)).isInstanceOf(IllegalStateException.class);
        settings.deleteConnection(id,2);
        assertThat(chats.session(session.id()).connectionId()).isNull();
    }
    @Test void customCaIsEncryptedPreservedAndOnlyAllowedOnHttps()throws Exception {
        String ca=KubeSphereGatewayTlsTest.pem("ca.pem");UUID id=UUID.randomUUID();
        var saved=settings.saveConnection(id,new ConsoleSettings.ConnectionInput(0,"TLS 集群","https://api.example.com","","platform-system","fake-token",false,ca,false));
        assertThat(saved.hasCaCertificate()).isTrue();
        assertThat(jdbc.queryForObject("SELECT payload::text FROM agent_console_connection",String.class)).doesNotContain("BEGIN CERTIFICATE","fake-token");
        assertThat(settings.connectionCaCertificate(settings.connection(id))).isEqualTo(ca.trim());
        settings.saveConnection(id,new ConsoleSettings.ConnectionInput(1,saved.name(),saved.apiUrl(),"",saved.namespace(),"",false));
        assertThat(settings.connections().getFirst().hasCaCertificate()).isTrue();
        assertThatThrownBy(()->settings.saveConnection(UUID.randomUUID(),new ConsoleSettings.ConnectionInput(0,"bad",base,"","default","",false,ca,false))).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->settings.saveConnection(UUID.randomUUID(),new ConsoleSettings.ConnectionInput(0,"bad","https://api.example.com","","default","",false,"bad-pem",false))).isInstanceOf(IllegalArgumentException.class);
        settings.saveConnection(id,new ConsoleSettings.ConnectionInput(2,saved.name(),saved.apiUrl(),"",saved.namespace(),"",false,"",true));
        assertThat(settings.connectionCaCertificate(settings.connection(id))).isEmpty();
    }
    @Test void rejectsCredentialBearingOrAmbiguousUrls() {
        for(String url:List.of("https://user:secret@example.com","https://example.com?token=x","file:///tmp/key","https://example.com/#x"))
            assertThatThrownBy(()->settings.validateUrl(url)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void chatIsIdempotentAndSerialWithinSession() {
        var session=chats.create("排障",null,null); UUID request=UUID.randomUUID();
        var claim=chats.begin(session.id(),request,"问题",false);
        assertThatThrownBy(()->chats.begin(session.id(),UUID.randomUUID(),"第二条",false)).isInstanceOf(IllegalStateException.class);
        var result=chats.finish(claim,"答案","长模型名称".repeat(30),true);
        assertThat(result.source()).hasSize(100);
        assertThat(chats.begin(session.id(),request,"问题",false).turn()).isEqualTo(result);
        assertThat(chats.begin(session.id(),request,"问题",false).replay()).isTrue();
        assertThatThrownBy(()->chats.begin(session.id(),request,"不同内容",false)).isInstanceOf(IllegalStateException.class);
        assertThat(chats.turns(session.id())).hasSize(1);
    }
    @Test void expiredChatOwnerCannotOverwriteNewerConversation() {
        var session=chats.create("排障",null,null);
        var old=chats.begin(session.id(),UUID.randomUUID(),"旧问题",false);
        jdbc.update("UPDATE agent_chat_turn SET lease_until=now()-interval '1 second' WHERE id=?",old.turn().id());
        var next=chats.begin(session.id(),UUID.randomUUID(),"新问题",false);
        assertThatThrownBy(()->chats.finish(old,"迟到结果","测试",true)).isInstanceOf(IllegalStateException.class);
        chats.finish(next,"有效结果","测试",true);
        assertThat(chats.turns(session.id())).extracting(ChatStore.Turn::state).containsExactly("FAILED","SUCCEEDED");
    }
    @Test void openAiGatewayUsesConfiguredPathAndStructuredMessages() throws Exception {
        reply.set("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"模拟回答\"},\"finish_reason\":\"stop\"}]}");
        settings.saveLlm(llm(0,base+"/compatible-mode/v1","fake-api-key",true,false));
        assertThat(models.complete(settings.llm().orElseThrow(),List.of(new UserMessage("问题 {literal}")))).isEqualTo("模拟回答");
        assertThat(path.get()).isEqualTo("/compatible-mode/v1/chat/completions");
        assertThat(authorization.get()).isEqualTo("Bearer fake-api-key");
        assertThat(json.readTree(body.get()).path("messages").get(0).path("content").asText()).isEqualTo("问题 {literal}");
    }
    @Test void dashScopeGatewayUsesNativeAlibabaProtocol() {
        reply.set("{\"output\":{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"role\":\"assistant\",\"content\":\"百炼模拟回答\"}}]},\"usage\":{\"input_tokens\":1,\"output_tokens\":2,\"total_tokens\":3},\"request_id\":\"test\"}");
        settings.saveLlm(new ConsoleSettings.LlmInput(0,true,ConsoleSettings.Provider.DASHSCOPE,base,"qwen-plus","fake-key",false,0.1,512));
        assertThat(models.complete(settings.llm().orElseThrow(),List.of(new UserMessage("你好")))).isEqualTo("百炼模拟回答");
        assertThat(path.get()).isEqualTo("/api/v1/services/aigc/text-generation/generation");
        assertThat(authorization.get()).isEqualTo("Bearer fake-key");
    }
    @Test void gatewayDoesNotFollowRedirectsOrEchoUpstreamSecrets() {
        status.set(302); reply.set("{\"error\":\"fake-api-key\"}");
        settings.saveLlm(llm(0,base+"/v1","fake-api-key",true,false));
        assertThatThrownBy(()->models.complete(settings.llm().orElseThrow(),List.of(new UserMessage("你好"))))
                .isInstanceOf(ConsoleFailure.class).hasMessageNotContaining("fake-api-key");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void clusterProbeReadsOnlyNamespacedPodsAndRejectsNonLists() {
        reply.set("{\"items\":[{\"metadata\":{\"name\":\"web-1\"},\"status\":{\"phase\":\"Running\",\"conditions\":[{\"type\":\"Ready\",\"status\":\"True\"}]}}]}");
        UUID id=UUID.randomUUID();settings.saveConnection(id,connection(0,base+"/clusters/dev","fake-token",false));
        var snapshot=clusters.inspect(settings.connection(id));
        assertThat(snapshot.pods()).containsExactly(new KubeSphereGateway.PodSummary("web-1","Running",true));
        assertThat(path.get()).isEqualTo("/clusters/dev/api/v1/namespaces/agent-demo/pods?limit=10");
        assertThat(authorization.get()).isEqualTo("Bearer fake-token");
        reply.set("{}");
        assertThatThrownBy(()->clusters.inspect(settings.connection(id))).isInstanceOf(ConsoleFailure.class);
        status.set(403);reply.set("{\"message\":\"fake-token\"}");
        assertThatThrownBy(()->clusters.inspect(settings.connection(id))).isInstanceOf(ConsoleFailure.class).hasMessageNotContaining("fake-token");
    }
    @Test void conversationSupportsFallbackThenHotModelConfigurationAndDurableHistory() {
        var session=chats.create("部署排障",null,null);
        var fallback=agent.send(session.id(),UUID.randomUUID(),"怎么排障？",false);
        assertThat(fallback.source()).isEqualTo("规则助手");
        reply.set("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"请核对镜像地址\"}}]}");
        settings.saveLlm(llm(0,base+"/v1","fake-api-key",true,false));
        UUID request=UUID.randomUUID();
        var response=agent.send(session.id(),request,"请继续",false);
        assertThat(response.assistantContent()).isEqualTo("请核对镜像地址");
        assertThat(response.source()).isEqualTo("LLM / test-model");
        assertThat(body.get()).contains("怎么排障？","请继续","后台工具已通过当前连接执行只读排查Graph");
        assertThat(agent.send(session.id(),request,"请继续",false)).isEqualTo(response);
        assertThat(calls.get()).isEqualTo(1);
        assertThat(chats.turns(session.id())).hasSize(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_run",Integer.class)).isZero();
    }
    @Test void savedModelAlsoControlsGraphNarrationAndFailureFallsBackToRunbook() throws Exception {
        String diagnosis="{\"cause\":\"模拟根因\",\"checks\":[\"核对事件\"],\"repairSteps\":[\"按手册处理\"],\"verification\":\"验证目标版本\"}";
        reply.set(json.writeValueAsString(Map.of("choices",List.of(Map.of("index",0,"message",Map.of("role","assistant","content",diagnosis))))));
        settings.saveLlm(llm(0,base+"/v1","fake-api-key",true,false));
        var result=narrator.explain("模拟证据",com.lanhao.ops.domain.Model.Action.RETRY_TRANSIENT);
        assertThat(result.cause()).isEqualTo("模拟根因");
        assertThat(result.source()).isEqualTo("控制台 LLM / test-model");
        status.set(503);
        assertThat(narrator.explain("模拟证据",com.lanhao.ops.domain.Model.Action.RETRY_TRANSIENT).source()).contains("回退");
        settings.saveLlm(llm(1,base+"/v1","",false,false));
        int before=calls.get();
        assertThat(narrator.explain("模拟证据",com.lanhao.ops.domain.Model.Action.RETRY_TRANSIENT).source()).contains("关闭 LLM");
        assertThat(calls.get()).isEqualTo(before);
    }
    @Test void publicShellAndAuthenticatedApiEnforceConsoleWriteOrigin() {
        assertThat(http.getForEntity("/",String.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(http.getForEntity("/api/console/bootstrap",String.class).getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        var auth=http.withBasicAuth("operator","demo-local-only");
        assertThat(auth.getForEntity("/api/console/bootstrap",JsonNode.class).getBody().path("username").asText()).isEqualTo("operator");
        var input=llm(0,base+"/v1","fake-key",false,false);
        assertThat(auth.exchange("/api/console/llm",HttpMethod.PUT,new HttpEntity<>(input),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        HttpHeaders headers=new HttpHeaders(); headers.set("X-Console-Request","1");headers.setOrigin("https://evil.example");
        assertThat(auth.exchange("/api/console/llm",HttpMethod.PUT,new HttpEntity<>(input,headers),String.class).getStatusCode()).isEqualTo(HttpStatus.FORBIDDEN);
        headers.remove("Origin");
        var response=auth.exchange("/api/console/llm",HttpMethod.PUT,new HttpEntity<>(input,headers),String.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).doesNotContain("fake-key","encryptedKey");
        var session=auth.postForEntity("/api/console/sessions",new HttpEntity<>(Map.of("title","token=private-title"),headers),String.class);
        assertThat(session.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(session.getBody()).doesNotContain("private-title").contains("已脱敏");
    }
}
