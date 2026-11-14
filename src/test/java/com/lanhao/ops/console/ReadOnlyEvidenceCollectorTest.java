package com.lanhao.ops.console;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.TestFixtures;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.evidence.Redactor;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ReadOnlyEvidenceCollectorTest {
    HttpServer server;ReadOnlyEvidenceCollector collector;ConsoleSettings.Connection connection;
    List<String> paths=Collections.synchronizedList(new ArrayList<>());
    AtomicBoolean forbidden=new AtomicBoolean(),repeatPage=new AtomicBoolean(),crowded=new AtomicBoolean(),credentialCursor=new AtomicBoolean();
    ObjectMapper json=TestFixtures.json();
    @BeforeEach void start()throws Exception {
        server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/",exchange->{
            assertThat(exchange.getRequestMethod()).isEqualTo("GET");
            assertThat(exchange.getRequestHeaders().getFirst("Authorization")).isEqualTo("Bearer fake-read-token");
            String path=exchange.getRequestURI().getPath(),query=Objects.toString(exchange.getRequestURI().getRawQuery(),"");paths.add(path+"?"+query);
            int code=200;Object payload=Map.of("items",List.of());
            if(path.endsWith("/jobs"))payload=Map.of("items",List.of(resource("install-job","job-uid","root-uid",Map.of())));
            if(path.endsWith("/jobs")&&crowded.get())payload=Map.of("items",List.of(resource("install-job","job-uid","root-uid",Map.of("failed",3,"conditions",List.of(Map.of("type","Failed","status","True","reason","BackoffLimitExceeded"))))));
            if(path.endsWith("/pods")) {
                if(forbidden.get()){code=403;payload=Map.of("message","fake-read-token must never leak");}
                else if(repeatPage.get())payload=Map.of("items",List.of(),"metadata",Map.of("continue","same-page"));
                else if(credentialCursor.get()&&!query.contains("continue="))payload=Map.of("items",List.of(),"metadata",Map.of("continue","fake-read-token"));
                else if(!query.contains("continue="))payload=Map.of("items",List.of(resource("unrelated-pod","unrelated-uid","other-root",Map.of())),"metadata",Map.of("continue","page-two"));
                else payload=Map.of("items",List.of(resource("target-pod","pod-uid","job-uid",Map.of("phase","Pending","containerStatuses",List.of(
                        Map.of("name","app","restartCount",3,"state",Map.of("waiting",Map.of("reason","ImagePullBackOff","message","registry unreachable fake-read-token"))),
                        Map.of("name","sidecar","state",Map.of("running",Map.of())))))));
                if(crowded.get()) {
                    var candidates=new ArrayList<Map<String,Object>>();
                    for(int i=0;i<5;i++)candidates.add(resource("a-completed-"+i,"completed-"+i,"job-uid",Map.of("phase","Succeeded","containerStatuses",List.of(Map.of("name","helm","state",Map.of("terminated",Map.of("exitCode",0,"reason","Completed")))))));
                    candidates.add(resource("z-failed-pod","failure-pod-uid","job-uid",Map.of("phase","Failed","containerStatuses",List.of(Map.of("name","helm","state",Map.of("terminated",Map.of("exitCode",1,"reason","Error")))))));
                    candidates.add(resource("unrelated-failed","other-pod-uid","unrelated-root",Map.of("phase","Failed")));
                    payload=Map.of("items",candidates);
                }
            }
            if(path.endsWith("/events")) {
                String owner=URLDecoder.decode(query,StandardCharsets.UTF_8).split("involvedObject.uid=")[1].split("&")[0];
                payload=Map.of("items",List.of(Map.of("involvedObject",Map.of("uid",owner),"reason","FailedScheduling","message","volume node affinity conflict"),
                        Map.of("involvedObject",Map.of("uid","not-owned"),"reason","UnrelatedEvent","message","must not include")));
            }
            if(path.endsWith("/pods/target-pod"))payload=resource("target-pod","pod-uid","job-uid",Map.of());
            if(path.endsWith("/pods/z-failed-pod"))payload=resource("z-failed-pod","failure-pod-uid","job-uid",Map.of());
            if(path.contains("/pods/a-completed-")) {
                String index=path.substring(path.lastIndexOf('-')+1);payload=resource("a-completed-"+index,"completed-"+index,"job-uid",Map.of());
            }
            byte[] bytes=path.endsWith("/log")?"bounded log token=fake-read-token\nfixture evidence".getBytes(StandardCharsets.UTF_8):json.writeValueAsBytes(payload);
            exchange.getResponseHeaders().set("Content-Type",path.endsWith("/log")?"text/plain":"application/json");
            exchange.sendResponseHeaders(code,bytes.length);try(var body=exchange.getResponseBody()){body.write(bytes);}
        });server.start();
        var settings=mock(ConsoleSettings.class);
        connection=new ConsoleSettings.Connection(UUID.randomUUID(),1,"fixture","http://127.0.0.1:"+server.getAddress().getPort(),"","agent-demo",null);
        when(settings.connectionToken(connection)).thenReturn("fake-read-token");when(settings.connectionCaCertificate(connection)).thenReturn("");
        collector=new ReadOnlyEvidenceCollector(settings,json,new Redactor());
    }
    @AfterEach void stop(){server.stop(0);}
    Map<String,Object> resource(String name,String uid,String owner,Map<String,Object> status) {
        return Map.of("metadata",Map.of("name",name,"namespace","agent-demo","uid",uid,"ownerReferences",List.of(Map.of("uid",owner))),"status",status);
    }
    DiscoveryGateway.Observation observation() {
        return new DiscoveryGateway.Observation(new Target(Kind.ProjectRuntime,"agent-demo","runtime","root-uid",1),"v1","project","common","Progressing",1,"COMPONENTS_PENDING","等待组件");
    }
    @Test void followsOwnerChainAndPaginationCollectsActualContainerEventsAndBoundedLogs()throws Exception {
        var evidence=collector.collect(connection,observation());String body=json.writeValueAsString(evidence);
        assertThat(body).contains("target-pod","ImagePullBackOff","restartCount=3","FailedScheduling","volume node affinity conflict","fixture evidence")
                .doesNotContain("unrelated-pod","UnrelatedEvent","must not include","fake-read-token");
        assertThat(evidence.issues()).isEmpty();assertThat(evidence.items()).allMatch(item->!item.decisive());
        assertThat(paths).anyMatch(path->path.contains("continue=page-two"));
        assertThat(paths).anyMatch(path->path.contains("tailLines=60&limitBytes=4096"));
        assertThat(paths).noneMatch(path->path.contains("secrets")||path.contains("exec"));
    }
    @Test void deniedOptionalReadsPreserveCrEvidenceAndExplicitGapWithoutUpstreamBody()throws Exception {
        forbidden.set(true);var evidence=collector.collect(connection,observation());
        assertThat(evidence.facts()).anyMatch(fact->fact.contains("COMPONENTS_PENDING"));
        assertThat(evidence.issues()).anyMatch(issue->issue.source().equals("PODS")&&issue.errorType().equals("HTTP_403"));
        assertThat(json.writeValueAsString(evidence)).doesNotContain("fake-read-token","must never leak");
    }
    @Test void repeatedPaginationStopsInsteadOfLoopingOrPretendingCompleteCoverage() {
        repeatPage.set(true);var evidence=collector.collect(connection,observation());
        assertThat(evidence.issues()).anyMatch(issue->issue.errorType().equals("PAGINATION_INVALID"));
        assertThat(paths.size()).isLessThan(12);
        assertThat(evidence.issues()).anyMatch(issue->issue.errorType().equals("NO_OWNED_PODS"));
    }
    @Test void requestAuditRedactsKnownCredentialEvenInsideServerPaginationCursor()throws Exception {
        credentialCursor.set(true);var displayed=new ArrayList<String>();
        var evidence=collector.collect(connection,observation(),java.time.Duration.ofSeconds(8),new ReadOnlyEvidenceCollector.ReadAudit(){
            public void before(String path,String displayPath){displayed.add(displayPath);}
        });
        assertThat(paths).anyMatch(path->path.contains("continue=fake-read-token"));
        assertThat(displayed).noneMatch(path->path.contains("fake-read-token"));
        assertThat(json.writeValueAsString(evidence)).doesNotContain("fake-read-token");
    }
    @Test void prioritizesFailedWorkloadPodsBeforeCompletedJobsAndReportsSamplingGap()throws Exception {
        crowded.set(true);var evidence=collector.collect(connection,observation());String body=json.writeValueAsString(evidence);
        assertThat(body).contains("WORKLOAD_FAILED","BackoffLimitExceeded","z-failed-pod","exitCode=1").doesNotContain("unrelated-failed","a-completed-4","fake-read-token");
        assertThat(evidence.issues()).anyMatch(i->i.errorType().equals("POD_SAMPLE_LIMIT"));
        assertThat(evidence.items().stream().filter(i->i.source().startsWith("CONTAINER/")).findFirst().orElseThrow().source()).contains("z-failed-pod");
        assertThat(paths.stream().filter(p->p.contains("/log?")).findFirst().orElseThrow()).contains("z-failed-pod");
        assertThat(paths.size()).isLessThanOrEqualTo(24);
    }
}
