package com.lanhao.ops.console;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.TestFixtures;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.springframework.web.client.RestClient;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ProvisioningGatewayTest {
    HttpServer server;
    ProvisioningGateway gateway;
    AtomicInteger code=new AtomicInteger(200),calls=new AtomicInteger();
    AtomicReference<String> body=new AtomicReference<>("{\"code\":200,\"data\":{\"items\":[],\"nextCursor\":\"next\",\"hasMore\":false}}"),query=new AtomicReference<>();
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/gateway/provisioning-stage-events",exchange->{
            calls.incrementAndGet();query.set(exchange.getRequestURI().getRawQuery());
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fixture-platform-token");
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.getResponseHeaders().set("Location","http://127.0.0.1:"+server.getAddress().getPort()+"/unexpected");
            byte[] bytes=body.get().getBytes(StandardCharsets.UTF_8);exchange.sendResponseHeaders(code.get(),bytes.length);try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        var config=mock(AgentProperties.class);when(config.platformBaseUrl()).thenReturn(URI.create("http://127.0.0.1:"+server.getAddress().getPort()+"/gateway"));when(config.platformToken()).thenReturn("fixture-platform-token");
        gateway=new ProvisioningGateway(config,new ProvisioningProperties(true,"platform-test",5,20,false,""),new PlatformCatalogProperties(false,5,true),RestClient.builder(),TestFixtures.json());
    }
    @AfterEach void stop(){server.stop(0);}
    @Test void encodesOpaqueCursorAsAValueAndKeepsGatewayPrefix() {
        gateway.page("cursor+ /?&=中文");
        assertThat(URLDecoder.decode(query.get().substring("cursor=".length()),StandardCharsets.UTF_8)).isEqualTo("cursor+ /?&=中文");
        assertThat(gateway.authorizationHash()).doesNotContain("fixture-platform-token");
    }
    @Test void refusesRedirectAndDoesNotExposeErrorBody() {
        code.set(302);body.set("secret-upstream-response");
        assertThatThrownBy(()->gateway.page("")).isInstanceOf(ConsoleFailure.class).hasMessageContaining("302").hasMessageNotContaining("secret-upstream-response");assertThat(calls).hasValue(1);
        code.set(403);assertThatThrownBy(()->gateway.page("")).hasMessageContaining("403").hasMessageNotContaining("secret-upstream-response");
    }
    @Test void expiredCursorAndNonAdvancingCursorAreDistinct() {
        code.set(410);assertThatThrownBy(()->gateway.page("old")).isInstanceOf(ProvisioningGateway.CursorExpired.class);
        code.set(200);body.set("{\"code\":200,\"data\":{\"items\":[],\"nextCursor\":\"old\",\"hasMore\":true}}");
        assertThatThrownBy(()->gateway.page("old")).hasMessageContaining("没有推进");
    }
    @Test void rejectsOversizedAndWrongShapeResponses() {
        body.set("x".repeat(2*1024*1024+1));assertThatThrownBy(()->gateway.page("")).hasMessageContaining("超过上限");
        body.set("{\"code\":200,\"data\":{\"items\":[],\"nextCursor\":\"next\",\"hasMore\":\"true\"}}");assertThatThrownBy(()->gateway.page("")).hasMessageContaining("分页契约");
    }
}
