package com.lanhao.ops.console;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(OutputCaptureExtension.class)
class ModelGatewayTest {
    HttpServer server;
    ExecutorService executor;
    AtomicInteger status=new AtomicInteger(200),calls=new AtomicInteger(),delay=new AtomicInteger();
    AtomicReference<String> reply=new AtomicReference<>("{\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"合成回复\"}}]}");
    ConsoleSettings settings;
    ConsoleSettings.Llm llm;
    String base;
    @BeforeEach void setup()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        base="http://127.0.0.1:"+server.getAddress().getPort();
        executor=Executors.newVirtualThreadPerTaskExecutor();server.setExecutor(executor);
        server.createContext("/",exchange->{
            calls.incrementAndGet();exchange.getRequestBody().readAllBytes();
            if(delay.get()>0)try{Thread.sleep(delay.get());}catch(InterruptedException e){Thread.currentThread().interrupt();return;}
            exchange.getResponseHeaders().set("Content-Type","application/json");
            if(status.get()==302)exchange.getResponseHeaders().set("Location",base+"/redirected");
            byte[] bytes=reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });server.start();
        settings=mock(ConsoleSettings.class);when(settings.llmKey(any())).thenReturn("fake-model-key");
        llm=new ConsoleSettings.Llm(1,true,ConsoleSettings.Provider.OPENAI_COMPATIBLE,base+"/v1","test-model","unused-cipher",0.1,512);
    }
    @AfterEach void stop(){server.stop(0);executor.shutdownNow();}
    ModelGateway gateway(Duration timeout){return new ModelGateway(settings,new ConsoleProperties(null,Path.of("unused"),true,4,timeout));}
    String complete(ModelGateway gateway){return gateway.complete(llm,List.of(new UserMessage("合成问题")));}

    @ParameterizedTest
    @CsvSource({"401,AUTHENTICATION","403,PERMISSION","404,ENDPOINT_NOT_FOUND","429,RATE_LIMIT","500,UPSTREAM_UNAVAILABLE","503,UPSTREAM_UNAVAILABLE","504,TIMEOUT","302,REDIRECT","400,INVALID_REQUEST"})
    void classifiesStatusWithoutLeakingBodyOrAutomaticallyRetrying(int http,ModelFailure.Code code,CapturedOutput output) {
        status.set(http);reply.set("{\"error\":{\"message\":\"fake-model-key upstream-private-detail\"}}");
        assertThatThrownBy(()->complete(gateway(Duration.ofSeconds(2))))
                .isInstanceOfSatisfying(ModelFailure.class,failure->{
                    assertThat(failure.code()).isEqualTo(code);assertThat(failure.status()).isEqualTo(http);
                    assertThat(failure.getMessage()).contains("LLM_"+code.name(),"HTTP "+http).doesNotContain("fake-model-key","upstream-private-detail");
                    assertThat(failure.getCause()).isNull();
                });
        assertThat(calls.get()).isEqualTo(1);
        assertThat(output.getAll()).doesNotContain("fake-model-key","upstream-private-detail");
    }
    @Test void distinguishesUnavailableModelFromWrongEndpoint() {
        status.set(400);reply.set("{\"error\":{\"code\":\"model_not_found\",\"message\":\"fake-model-key\"}}");
        assertThatThrownBy(()->complete(gateway(Duration.ofSeconds(2)))).isInstanceOfSatisfying(ModelFailure.class,f->assertThat(f.code()).isEqualTo(ModelFailure.Code.MODEL_UNAVAILABLE));
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void timeoutIsBoundedAndNeverReplayed() {
        delay.set(600);long started=System.nanoTime();
        assertThatThrownBy(()->complete(gateway(Duration.ofMillis(150)))).isInstanceOfSatisfying(ModelFailure.class,f->assertThat(f.code()).isEqualTo(ModelFailure.Code.TIMEOUT));
        assertThat(Duration.ofNanos(System.nanoTime()-started)).isLessThan(Duration.ofSeconds(3));
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void supportsSlowerRepliesWithinConfiguredBudget() {
        delay.set(250);assertThat(complete(gateway(Duration.ofSeconds(2)))).isEqualTo("合成回复");
        assertThat(calls.get()).isEqualTo(1);
    }
    @Test void malformedAndEmptyRepliesAreIdentifiedWithoutEchoingResponse() {
        reply.set("not-json fake-model-key");
        assertThatThrownBy(()->complete(gateway(Duration.ofSeconds(2)))).isInstanceOfSatisfying(ModelFailure.class,f->assertThat(f.code()).isEqualTo(ModelFailure.Code.INVALID_RESPONSE));
        reply.set("{\"choices\":[]}");
        assertThatThrownBy(()->complete(gateway(Duration.ofSeconds(2)))).isInstanceOfSatisfying(ModelFailure.class,f->assertThat(f.code()).isEqualTo(ModelFailure.Code.EMPTY_RESPONSE));
        assertThat(calls.get()).isEqualTo(2);
    }
    @Test void validatesBudgetAgainstOuterChatTimeout() {
        try(var factory=jakarta.validation.Validation.buildDefaultValidatorFactory()) {
            var validator=factory.getValidator();
            assertThat(validator.validate(new ConsoleProperties(null,Path.of("unused"),true,4,Duration.ofSeconds(46)))).isNotEmpty();
            assertThat(validator.validate(new ConsoleProperties(null,Path.of("unused"),true,4,Duration.ofSeconds(45)))).isEmpty();
        }
    }
}
