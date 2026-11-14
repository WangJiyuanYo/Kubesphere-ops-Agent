package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.*;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DiscoveryGatewayTest {
    HttpServer server;
    ConsoleSettings settings=mock(ConsoleSettings.class);
    ObjectMapper json=new ObjectMapper();
    DiscoveryGateway gateway=new DiscoveryGateway(settings,json,new Redactor());
    ConsoleSettings.Connection connection;
    List<String> paths=new ArrayList<>();
    AtomicInteger code=new AtomicInteger(200);
    AtomicReference<String> response=new AtomicReference<>();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        connection=new ConsoleSettings.Connection(UUID.randomUUID(),1,"scope","http://127.0.0.1:"+server.getAddress().getPort()+"/proxy","","team-a","encrypted");
        when(settings.connectionToken(connection)).thenReturn("fake-discovery-key");when(settings.connectionCaCertificate(connection)).thenReturn("");
        server.createContext("/",exchange->{
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fake-discovery-key");
            paths.add(exchange.getRequestURI().toString());
            if(code.get()==302)exchange.getResponseHeaders().set("Location",connection.apiUrl()+"/redirect");
            byte[] bytes=Objects.toString(response.get(),"{\"items\":[]}").getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(code.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
    }
    @AfterEach void stop(){server.stop(0);}
    static String resource(String kind,String namespace,String uid,int generation,String phase,int observed) {
        return """
                {"apiVersion":"platform.lanhao.io/v1alpha1","kind":"%s","metadata":{"name":"demo-app","namespace":"%s","uid":"%s","generation":%d,"labels":{"platform.lanhao.io/project-id":"123"}},
                 "spec":{"releaseVersion":"v2","password":"never-persist-spec"},"status":{"phase":"%s","observedGeneration":%d,"reason":"REGISTRY_TIMEOUT","message":"token=never-persist-token"}}
                """.formatted(kind,namespace,uid,generation,phase,observed);
    }
    @Test void readsOnlyNamespacedOperatorListsAndEncodesOpaquePagingTokens() {
        response.set("{\"items\":[]}");
        assertThat(gateway.scan(connection)).isEmpty();
        assertThat(paths).hasSize(4).allMatch(p->p.startsWith("/proxy/apis/platform.lanhao.io/v1alpha1/namespaces/team-a/"));
        assertThat(paths).allMatch(p->p.endsWith("?limit=100"));
        assertThat(paths).noneMatch(p->p.contains("secrets")||p.contains("pods"));
    }
    @Test void scansOnlyExplicitNamespacesWithSharedPagingBudget() {
        response.set("{\"items\":[]}");
        assertThat(gateway.scan(connection,List.of("team-a","team-b"))).isEmpty();
        assertThat(paths).hasSize(8);
        assertThat(paths.stream().filter(p->p.contains("/namespaces/team-a/"))).hasSize(4);
        assertThat(paths.stream().filter(p->p.contains("/namespaces/team-b/"))).hasSize(4);
        assertThatThrownBy(()->gateway.scan(connection,List.of("*"))).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rechecksUidGenerationAndNamespaceBeforeAcceptingEvidence()throws Exception {
        var target=new Target(Kind.PlatformRelease,"team-a","demo-app","uid-1",2);
        response.set(resource("PlatformRelease","team-a","uid-1",2,"Failed",2));
        var observation=gateway.read(connection,target);
        assertThat(observation.evidence().failed()).isTrue();
        assertThat(json.writeValueAsString(observation)).contains("[已脱敏]").doesNotContain("never-persist-spec","never-persist-token");
        response.set(resource("PlatformRelease","team-a","uid-1",2,"Failed",2).replace("token=never-persist-token","fake-discovery-key"));
        assertThat(json.writeValueAsString(gateway.read(connection,target))).contains("[已脱敏]").doesNotContain("fake-discovery-key");
        response.set(resource("PlatformRelease","team-a","uid-1",2,"Failed",1));
        assertThat(gateway.read(connection,target).evidence().failed()).isFalse();
        for(String payload:List.of(resource("PlatformRelease","team-a","uid-other",2,"Failed",2),resource("PlatformRelease","team-a","uid-1",3,"Failed",3),resource("PlatformRelease","team-b","uid-1",2,"Failed",2))) {
            response.set(payload);assertThatThrownBy(()->gateway.read(connection,target)).isInstanceOf(ConsoleFailure.class);
        }
    }
    @Test void neverFollowsRedirectsOrEchoesUpstreamSecrets() {
        response.set("fake-discovery-key");
        for(int status:List.of(302,401,403,404,410,429,503)) {
            code.set(status);int before=paths.size();
            assertThatThrownBy(()->gateway.scan(connection)).isInstanceOf(ConsoleFailure.class).hasMessageContaining("HTTP "+status).hasMessageNotContaining("fake-discovery-key");
            assertThat(paths).hasSize(before+1);
        }
    }
    @Test void rejectsOversizedMalformedAndLoopingSnapshotsAsIncomplete() {
        for(String payload:List.of(" ".repeat(2_097_153),"{}","null","not-json","{\"items\":[],\"metadata\":{\"continue\":\"opaque&token=/next\"}}")) {
            response.set(payload);assertThatThrownBy(()->gateway.scan(connection)).isInstanceOf(ConsoleFailure.class);
        }
        assertThat(paths).anyMatch(p->p.contains("continue=opaque%26token%3D%2Fnext"));
    }
}
