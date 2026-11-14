package com.lanhao.ops;

import com.lanhao.ops.command.*;
import com.lanhao.ops.domain.Model.*;
import com.sun.net.httpserver.HttpServer;
import io.fabric8.kubernetes.client.*;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;
import static com.lanhao.ops.command.CommandModel.*;
import static org.assertj.core.api.Assertions.*;

class CommandScopeResolverTest {
    HttpServer server;KubernetesClient client;CommandScopeResolver resolver;String uid="uid-1";boolean denied;
    Scope scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),"owner",TestFixtures.request("scope").targets(),Instant.now().plusSeconds(45));
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            String path=exchange.getRequestURI().getPath();String body;
            if(path.contains("projectruntimes/"))body="{\"apiVersion\":\"platform.lanhao.io/v1alpha1\",\"kind\":\"ProjectRuntime\",\"metadata\":{\"name\":\"runtime\",\"uid\":\""+uid+"\"}}";
            else if(path.endsWith("deployments"))body=list("Deployment",item("dep","dep-uid","uid-1",false));
            else if(path.endsWith("replicasets"))body=list("ReplicaSet",item("rs","rs-uid","dep-uid",false));
            else if(path.endsWith("pods"))body=list("Pod",item("mine","pod-uid","rs-uid",true)+","+item("other","other-uid","other-deploy",true));
            else body="{\"apiVersion\":\"v1\",\"kind\":\"List\",\"items\":[]}";
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);exchange.getResponseHeaders().set("Content-Type","application/json");
            exchange.sendResponseHeaders(denied?403:200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        client=new KubernetesClientBuilder().withConfig(new ConfigBuilder().withMasterUrl("http://127.0.0.1:"+server.getAddress().getPort()).withRequestTimeout(2000).build()).build();
        var beanFactory=new StaticListableBeanFactory();beanFactory.addBean("client",client);var d=TestFixtures.config(false);
        var real=new AgentProperties(AgentProperties.Mode.KUBERNETES,false,false,d.maxAttempts(),d.stallTimeout(),d.runTimeout(),d.retryBackoff(),d.leaseDuration(),d.verificationTimeout(),d.stableChecks(),d.namespaces(),d.apiUsername(),d.apiPassword(),null,null,null);
        resolver=new CommandScopeResolver(real,beanFactory.getBeanProvider(KubernetesClient.class));
    }
    String list(String kind,String items){return "{\"apiVersion\":\"v1\",\"kind\":\""+kind+"List\",\"items\":["+items+"]}";}
    String item(String name,String uid,String owner,boolean pod){return "{\"apiVersion\":\"v1\",\"metadata\":{\"namespace\":\"agent-demo\",\"name\":\""+name+"\",\"uid\":\""+uid+"\",\"ownerReferences\":[{\"uid\":\""+owner+"\"}]}"+(pod?",\"spec\":{\"containers\":[{\"name\":\"app\",\"image\":\"test\"}]}":"")+"}";}
    @AfterEach void close(){client.close();server.stop(0);}
    @Test void onlyPodsInBoundCrOwnershipChainEnterCatalog() {
        var catalog=resolver.resolve(scope);assertThat(catalog.issue()).isEmpty();
        assertThat(catalog.subjects()).extracting(Subject::id).containsExactly("uid-1","pod-uid:app");
        assertThat(catalog.subjects()).noneMatch(s->s.name().equals("other"));
    }
    @Test void replacedCrPermissionFailureAndExpiredBudgetFailClosed() {
        uid="replacement";assertThat(resolver.resolve(scope).subjects()).isEmpty();
        uid="uid-1";denied=true;assertThat(resolver.resolve(scope).issue()).isNotBlank();
        assertThat(resolver.resolve(new Scope(scope.batchId(),scope.runId(),scope.leaseOwner(),scope.targets(),Instant.EPOCH)).subjects()).isEmpty();
    }
}
