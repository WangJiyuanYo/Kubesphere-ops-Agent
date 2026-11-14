package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;

/** Real HTTP transport and audit observer, synthetic Kubernetes payloads. */
final class ChatInspectionFixture implements AutoCloseable {
    final HttpServer server;
    final String base;
    final AtomicReference<UUID> turn=new AtomicReference<>();
    final AtomicInteger calls=new AtomicInteger(),podGets=new AtomicInteger();
    final AtomicBoolean everyReadAudited=new AtomicBoolean(true),replacePod=new AtomicBoolean();
    final List<String> paths=Collections.synchronizedList(new ArrayList<>());
    volatile int eventsCode=200,logsCode=200;
    volatile Runnable onLog=()->{};
    ChatInspectionFixture(ChatInspectionStore audit,ObjectMapper json)throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        base="http://127.0.0.1:"+server.getAddress().getPort();
        server.createContext("/",exchange->{
            calls.incrementAndGet();String path=exchange.getRequestURI().toString();paths.add(path);
            UUID id=turn.get();
            boolean saved=id!=null&&audit.find(id).map(report->!report.requests().isEmpty()
                    &&report.requests().getLast().state().equals("RUNNING")
                    &&report.requests().getLast().request().equals("GET "+path)).orElse(false);
            if(!saved||!exchange.getRequestMethod().equals("GET")
                    ||!Objects.equals(exchange.getRequestHeaders().getFirst("Authorization"),"Bearer fixture-private-token"))everyReadAudited.set(false);
            Object value=Map.of("items",List.of());int status=200;boolean text=false;
            if(exchange.getRequestURI().getPath().endsWith("/pods"))value=Map.of("items",List.of(pod("pod-uid")));
            else if(exchange.getRequestURI().getPath().endsWith("/pods/broken-web")) {
                int count=podGets.incrementAndGet();value=pod(replacePod.get()&&count>=3?"replacement-uid":"pod-uid");
            } else if(exchange.getRequestURI().getPath().endsWith("/events")) {
                status=eventsCode;value=Map.of("items",List.of(Map.of("involvedObject",Map.of("uid","pod-uid"),"reason","BackOff","message","restarting failed container"),
                        Map.of("involvedObject",Map.of("uid","other-uid"),"message","unrelated event must not leak")));
            }else if(exchange.getRequestURI().getPath().endsWith("/log")) {
                onLog.run();status=logsCode;text=status==200;
                value=path.contains("previous=true")?"previous: RabbitMQ ECONNREFUSED\ntoken=fixture-private-token":"current: waiting for dependency\nfixture-private-token";
            }else status=404;
            if(status!=200){value=Map.of("message","fixture-private-token upstream-private-denial");text=false;}
            byte[] bytes=text?value.toString().getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(value);
            exchange.getResponseHeaders().set("Content-Type",text?"text/plain":"application/json");
            exchange.sendResponseHeaders(status,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
        });server.start();
    }
    private Map<String,Object> pod(String uid) {
        return Map.of("metadata",Map.of("namespace","agent-demo","name","broken-web","uid",uid),
                "status",Map.of("phase","Running","containerStatuses",List.of(Map.of("name","app","restartCount",2,
                        "state",Map.of("waiting",Map.of("reason","CrashLoopBackOff")),
                        "lastState",Map.of("terminated",Map.of("exitCode",1,"reason","Error"))))));
    }
    public void close(){server.stop(0);}
}
