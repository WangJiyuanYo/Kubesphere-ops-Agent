package com.lanhao.ops;

import com.lanhao.ops.command.*;
import com.lanhao.ops.evidence.Redactor;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.*;
import static com.lanhao.ops.command.CommandModel.*;
import static org.assertj.core.api.Assertions.*;

class CommandSafetyTest {
    private final KubectlProperties cli=new KubectlProperties(true,"","","",Duration.ofSeconds(5),3);
    private final CommandSafety safety=new CommandSafety(TestFixtures.config(true),cli,new Redactor());
    private final Subject pod=new Subject("pod-uid:app","pods","agent-demo","app-pod","pod-uid","app","uid-1");
    private final Catalog catalog=new Catalog(List.of(pod),"");
    @Test void onlyTypedReadonlyCommandsCanProduceArguments() {
        for(String operation:List.of("delete","apply","PATCH","exec","proxy","port-forward","GET; delete pods","GET --all-namespaces","$(whoami)","GET\nLOGS"))
            assertThat(safety.review(new Proposal(operation,pod.id(),""),catalog).allowed()).as(operation).isFalse();
        var logs=safety.review(new Proposal("LOGS",pod.id(),"排查"),catalog);
        assertThat(logs.allowed()).isTrue();
        assertThat(logs.argv()).containsExactly("kubectl","--namespace=agent-demo","--request-timeout=4s","logs","app-pod","--container=app","--tail=60","--limit-bytes=4096");
    }
    @Test void unknownSubjectCrossNamespaceSecretsAndFlagInjectionAreDenied() {
        assertThat(safety.review(new Proposal("GET","--context=prod",""),catalog).allowed()).isFalse();
        for(Subject wrong:List.of(new Subject("x","secrets","agent-demo","secret","u","","u"),new Subject("x","pods","kube-system","x","u","app","u"),
                new Subject("x","pods","agent-demo","--all-namespaces","u","app","u"),new Subject("x","pods","agent-demo","pod;whoami","u","app","u")))
            assertThat(safety.review(new Proposal("GET","x",""),new Catalog(List.of(wrong),"")).allowed()).isFalse();
    }
    @Test void catalogFailureAndInvalidLogsFailClosed() {
        assertThat(safety.review(new Proposal("GET",pod.id(),""),new Catalog(List.of(pod),"采集缺口")).allowed()).isFalse();
        var cr=new Subject("u","projectruntimes.platform.lanhao.io","agent-demo","app","u","","u");
        assertThat(safety.review(new Proposal("LOGS","u",""),new Catalog(List.of(cr),"")).allowed()).isFalse();
    }
    @Test void eventQueryIsBoundToResourceUidAndPurposeNeverBecomesAnArgument() {
        var review=safety.review(new Proposal("EVENTS",pod.id(),"; kubectl delete pvc --all token=secret"),catalog);
        assertThat(review.allowed()).isTrue();assertThat(review.argv()).contains("--field-selector=involvedObject.uid=pod-uid").doesNotContain("delete","pvc","secret");
        assertThat(review.proposal().purpose()).doesNotContain("token=secret");
    }
    @Test void nativeModeRejectsImplicitIdentityAndShellExecutables() {
        assertThatThrownBy(()->cli.validateNative()).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(()->new KubectlProperties(true,"cmd.exe","config","default",Duration.ofSeconds(5),3).validateNative()).isInstanceOf(IllegalArgumentException.class);
    }
}
