package com.lanhao.ops.console;

import com.lanhao.ops.evidence.Redactor;
import com.sun.net.httpserver.*;
import org.junit.jupiter.api.*;
import javax.net.ssl.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class KubeSphereGatewayTlsTest {
    HttpsServer server;
    final AtomicReference<String> authorization=new AtomicReference<>(),path=new AtomicReference<>();
    ConsoleSettings settings;
    KubeSphereGateway gateway;
    @BeforeEach void setup()throws Exception {
        KeyStore keys=KeyStore.getInstance("PKCS12");
        try(var input=getClass().getResourceAsStream("/tls/server.p12")){keys.load(input,"test-only".toCharArray());}
        KeyManagerFactory managers=KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        managers.init(keys,"test-only".toCharArray());
        SSLContext context=SSLContext.getInstance("TLS");context.init(managers.getKeyManagers(),null,null);
        server=HttpsServer.create(new InetSocketAddress("127.0.0.1",0),0);server.setHttpsConfigurator(new HttpsConfigurator(context));
        server.createContext("/",exchange->{
            authorization.set(exchange.getRequestHeaders().getFirst("Authorization"));path.set(exchange.getRequestURI().toString());
            byte[] body="{\"items\":[]}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            try(var output=exchange.getResponseBody()){output.write(body);}
        });server.start();
        settings=mock(ConsoleSettings.class);gateway=new KubeSphereGateway(settings,new Redactor());
    }
    @AfterEach void stop(){server.stop(0);}
    ConsoleSettings.Connection connection(String host,String ca)throws Exception {
        var connection=new ConsoleSettings.Connection(UUID.randomUUID(),1,"local TLS","https://"+host+":"+server.getAddress().getPort(),"","platform-system","encrypted-token","encrypted-ca");
        when(settings.connectionToken(connection)).thenReturn("fake-tls-token");
        when(settings.connectionCaCertificate(connection)).thenReturn(ca);
        return connection;
    }
    static String pem(String name)throws Exception {
        try(var input=KubeSphereGatewayTlsTest.class.getResourceAsStream("/tls/"+name)){return new String(input.readAllBytes(),StandardCharsets.US_ASCII);}
    }
    @Test void dedicatedCaEnablesNamespacedReadAndDoesNotChangeDefaultTrust()throws Exception {
        var trusted=connection("localhost",pem("ca.pem"));
        assertThat(gateway.inspect(trusted).connected()).isTrue();
        assertThat(path.get()).isEqualTo("/api/v1/namespaces/platform-system/pods?limit=10");
        assertThat(authorization.get()).isEqualTo("Bearer fake-tls-token");
        authorization.set(null);
        assertThatThrownBy(()->gateway.inspect(connection("localhost",""))).isInstanceOf(ConsoleFailure.class);
        assertThat(authorization.get()).isNull();
    }
    @Test void wrongCaRejectsTlsBeforeSendingToken()throws Exception {
        var wrong=connection("localhost",pem("wrong-ca.pem"));
        assertThatThrownBy(()->gateway.inspect(wrong)).isInstanceOf(ConsoleFailure.class).hasMessageNotContaining("fake-tls-token");
        assertThat(authorization.get()).isNull();
    }
    @Test void correctCaStillRequiresMatchingHostname()throws Exception {
        var mismatch=connection("127.0.0.1",pem("ca.pem"));
        assertThatThrownBy(()->gateway.inspect(mismatch)).isInstanceOf(ConsoleFailure.class);
        assertThat(authorization.get()).isNull();
    }
    @Test void rejectsLeafPrivateKeyGarbageAndOversizeCertificates()throws Exception {
        for(String invalid:List.of(pem("leaf.pem"),pem("ca.pem")+"\n-----BEGIN PRIVATE KEY-----\nAAAA\n-----END PRIVATE KEY-----","not-a-certificate","A".repeat(32769)))
            assertThatThrownBy(()->ConnectionTls.validate(invalid)).isInstanceOf(IllegalArgumentException.class);
        assertThat(ConnectionTls.validate("  ")).isNull();
        assertThat(ConnectionTls.validate(pem("ca.pem"))).contains("BEGIN CERTIFICATE");
    }
}
