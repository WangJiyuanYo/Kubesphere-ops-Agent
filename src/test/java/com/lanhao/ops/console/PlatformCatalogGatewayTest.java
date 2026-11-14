package com.lanhao.ops.console;

import com.lanhao.ops.TestFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.http.*;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.*;
import static org.springframework.test.web.client.response.MockRestResponseCreators.*;
import static org.assertj.core.api.Assertions.*;

class PlatformCatalogGatewayTest {
    @org.springframework.context.annotation.Configuration(proxyBeanMethods=false)
    @org.springframework.boot.context.properties.EnableConfigurationProperties(PlatformCatalogProperties.class)
    static class CatalogConfig {}

    @Test void bindsLocalOptInWhileKeepingDefaultDisabled() {
        var context=new org.springframework.boot.test.context.runner.ApplicationContextRunner().withUserConfiguration(CatalogConfig.class);
        context.run(app->{assertThat(app).hasNotFailed();assertThat(app.getBean(PlatformCatalogProperties.class).allowLoopbackHttp()).isFalse();});
        context.withPropertyValues("agent.platform-catalog.allow-loopback-http=true").run(app->{
            assertThat(app).hasNotFailed();assertThat(app.getBean(PlatformCatalogProperties.class).allowLoopbackHttp()).isTrue();
        });
    }

    @Test void loopbackHttpRequiresExplicitOptInAndRejectsRemoteAndAmbiguousAddresses() {
        var strict=new PlatformCatalogProperties(true,1);
        var local=new PlatformCatalogProperties(true,1,true);
        assertThat(strict.accepts(java.net.URI.create("http://127.0.0.1:8081/agent"))).isFalse();
        for(String base:java.util.List.of("http://127.0.0.1:8081/agent","http://[::1]:8081/agent")) {
            assertThat(local.accepts(java.net.URI.create(base))).as(base).isTrue();
        }
        for(String base:java.util.List.of("http://localhost:8081/agent","http://127.1:8081/agent",
                "http://127.0.0.2:8081/agent","http://2130706433:8081/agent","http://192.168.3.1/agent",
                "http://127.0.0.1.example/agent","http://127.0.0.1:8081/agent/",
                "http:///agent","http://u:p@127.0.0.1:8081/agent","http://127.0.0.1:8081/agent?target=remote",
                "http://127.0.0.1:8081/agent#fragment")) {
            assertThat(local.accepts(java.net.URI.create(base))).as(base).isFalse();
        }
        assertThat(local.accepts(null)).isFalse();
        assertThat(strict.accepts(java.net.URI.create("https://platform.example/agent"))).isTrue();
    }

    @Test void realHttpLoopbackReadsAuthorizedCatalogAndNeverFollowsRedirects()throws Exception {
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        var redirected=new java.util.concurrent.atomic.AtomicInteger();
        var requests=new java.util.concurrent.atomic.AtomicInteger();
        server.createContext("/agent/provisioning-instances",exchange->{
            requests.incrementAndGet();
            if(!"Bearer test-only-token".equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
                exchange.sendResponseHeaders(401,-1);exchange.close();return;
            }
            if(requests.get()>1) {
                exchange.getResponseHeaders().set("Location","/redirected");exchange.sendResponseHeaders(302,-1);exchange.close();return;
            }
            byte[] body=("{\"code\":200,\"data\":{\"items\":["+item+"],\"hasMore\":false}}").getBytes(java.nio.charset.StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });
        server.createContext("/redirected",exchange->{redirected.incrementAndGet();exchange.sendResponseHeaders(500,-1);exchange.close();});
        server.start();
        try {
            var base=java.net.URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/agent");
            var defaults=TestFixtures.config(false);
            var config=new com.lanhao.ops.AgentProperties(defaults.mode(),false,false,defaults.maxAttempts(),defaults.stallTimeout(),defaults.runTimeout(),
                    defaults.retryBackoff(),defaults.leaseDuration(),defaults.verificationTimeout(),defaults.stableChecks(),defaults.namespaces(),
                    defaults.apiUsername(),defaults.apiPassword(),base,"test-only-token",null);
            var gateway=new PlatformCatalogGateway(config,new PlatformCatalogProperties(true,1,true),RestClient.builder(),TestFixtures.json());
            assertThat(gateway.scan().items()).hasSize(1);
            assertThatThrownBy(gateway::scan).isInstanceOf(ConsoleFailure.class).hasMessageContaining("HTTP 302");
            assertThat(redirected.get()).isZero();
            assertThatThrownBy(()->new PlatformCatalogGateway(config,new PlatformCatalogProperties(true,1),RestClient.builder(),TestFixtures.json()))
                    .isInstanceOf(IllegalArgumentException.class);
            var live=new com.lanhao.ops.AgentProperties(com.lanhao.ops.AgentProperties.Mode.KUBERNETES,false,false,defaults.maxAttempts(),defaults.stallTimeout(),defaults.runTimeout(),
                    defaults.retryBackoff(),defaults.leaseDuration(),defaults.verificationTimeout(),defaults.stableChecks(),defaults.namespaces(),
                    defaults.apiUsername(),"independent-long-password",base,"test-only-token",null);
            assertThat(live.isLiveSettingsValid()).isFalse();
        }finally{server.stop(0);}
    }

    @Test void consumesFixtureExportedByTenantContractServiceWithoutRecoveryConfiguration()throws Exception {
        String body;
        try(var resource=getClass().getResourceAsStream("/contracts/tenant-catalog-entry.json")) {
            body=new String(java.util.Objects.requireNonNull(resource).readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
        }
        var builder=RestClient.builder().baseUrl("https://platform.example/agent");var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://platform.example/agent/provisioning-instances/9007199254740993/catalog-entry"))
                .andExpect(header("Accept","application/json"))
                .andRespond(withSuccess(body,MediaType.APPLICATION_JSON));
        var result=new PlatformCatalogGateway(new PlatformCatalogProperties(true,1),TestFixtures.json(),builder.build(),"test").current("9007199254740993");
        assertThat(result.id()).isEqualTo("9007199254740993");assertThat(result.siteId()).isEqualTo("9007199254740995");
        assertThat(result.namespace()).isEqualTo("team-app");assertThat(result.clusterApiUrl()).isEqualTo("https://cluster.example:6443");
        assertThat(result.version()).startsWith("provision-9007199254740993-");assertThat(result.unavailableReason()).isEmpty();mock.verify();
    }
    String item="{\"id\":\"9007199254740993\",\"siteId\":\"9007199254740995\",\"version\":\"provision-cycle\",\"status\":\"RUNNING\",\"ignoredCredential\":\"must-not-keep\"}";
    @Test void readsBoundedPaginatedDirectoryAndKeepsStringIdsWithoutUnknownPayloadFields()throws Exception {
        var builder=RestClient.builder().baseUrl("https://platform.example/agent");var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://platform.example/agent/provisioning-instances?page=1")).andRespond(withSuccess("{\"code\":200,\"data\":{\"items\":["+item+"],\"hasMore\":true}}",MediaType.APPLICATION_JSON));
        var gateway=new PlatformCatalogGateway(new PlatformCatalogProperties(true,1),TestFixtures.json(),builder.build(),"test");
        var result=gateway.scan();assertThat(result.partial()).isTrue();assertThat(result.items().getFirst().id()).isEqualTo("9007199254740993");
        assertThat(TestFixtures.json().writeValueAsString(result)).doesNotContain("must-not-keep","ignoredCredential");mock.verify();
    }
    @Test void rejectsAuthorizationFailureWithoutEchoingUpstreamBody() {
        var builder=RestClient.builder().baseUrl("https://platform.example/agent");var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(anything()).andRespond(withStatus(HttpStatus.FORBIDDEN).body("upstream-secret-details"));
        var gateway=new PlatformCatalogGateway(new PlatformCatalogProperties(true,1),TestFixtures.json(),builder.build(),"test");
        assertThatThrownBy(gateway::scan).isInstanceOf(ConsoleFailure.class).hasMessageContaining("授权失败").hasMessageNotContaining("upstream-secret-details");mock.verify();
    }
    @Test void readsDedicatedCatalogEntryWithoutDependingOnRecoverySnapshot() {
        var builder=RestClient.builder().baseUrl("https://platform.example/agent");var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(requestTo("https://platform.example/agent/provisioning-instances/9007199254740993/catalog-entry"))
                .andRespond(withSuccess("{\"code\":200,\"data\":"+item+"}",MediaType.APPLICATION_JSON));
        var gateway=new PlatformCatalogGateway(new PlatformCatalogProperties(true,1),TestFixtures.json(),builder.build(),"test");
        assertThat(gateway.current("9007199254740993").version()).isEqualTo("provision-cycle");mock.verify();
    }
    @Test void ignoresUnrecognizedSnapshotFieldsAndRejectsReturnedWrongTaskId() {
        var builder=RestClient.builder().baseUrl("https://platform.example/agent");var mock=MockRestServiceServer.bindTo(builder).build();
        mock.expect(anything()).andRespond(withSuccess("{\"code\":200,\"data\":"+item+"}",MediaType.APPLICATION_JSON));
        var gateway=new PlatformCatalogGateway(new PlatformCatalogProperties(true,1),TestFixtures.json(),builder.build(),"test");
        assertThatThrownBy(()->gateway.current("9007199254740994")).isInstanceOf(ConsoleFailure.class).hasMessageContaining("身份不匹配");mock.verify();
    }
}
