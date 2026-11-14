package com.lanhao.ops;

import com.lanhao.ops.command.*;
import com.lanhao.ops.evidence.Redactor;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static com.lanhao.ops.command.CommandModel.*;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class CommandWorkflowTest {
    KubectlProperties cli=new KubectlProperties(true,"/trusted/kubectl","/trusted/config","readonly",Duration.ofSeconds(5),3);
    AgentProperties config=KubectlIdentityTest.real();
    CommandScopeResolver scopes=mock(CommandScopeResolver.class);CommandAudit audit=mock(CommandAudit.class);
    BoundedProcess process=mock(BoundedProcess.class);KubectlIdentity identity=mock(KubectlIdentity.class);
    CommandSafety safety=new CommandSafety(config,cli,new Redactor());
    Scope scope=new Scope(UUID.randomUUID(),UUID.randomUUID(),"owner",TestFixtures.request("test").targets(),Instant.now().plusSeconds(45));
    Subject subject=new Subject("uid-1","projectruntimes.platform.lanhao.io","agent-demo","runtime","uid-1","","uid-1");
    Catalog catalog=new Catalog(List.of(subject),"");
    Work work(){var proposal=new Proposal("GET","uid-1","状态");var review=safety.review(proposal,catalog);return new Work(scope,catalog,List.of(proposal),List.of(review),List.of(new Execution(UUID.randomUUID(),review,"APPROVED","",null,Instant.now())),"模型","",false);}
    KubectlWorkflow workflow(){return new KubectlWorkflow(cli,config,mock(CommandPlanner.class),scopes,safety,audit,process,new Redactor(),identity);}
    @Test void persistsIntentBeforeProcessAndSanitizesOutputBeforeCompletion() {
        when(identity.unchanged()).thenReturn(true);when(scopes.resolve(scope)).thenReturn(catalog);
        when(process.run(anyList(),any())).thenReturn(new BoundedProcess.Result("SUCCEEDED","password=do-not-store OOMKilled",0));
        var work=work();var result=workflow().execute(work);
        var order=inOrder(audit,process);order.verify(audit).guard(scope);order.verify(audit).start(scope,work.results().getFirst());
        order.verify(audit).guard(scope);order.verify(process).run(work.reviews().getFirst().argv(),cli.timeout());order.verify(audit).finish(eq(scope),any());
        assertThat(result.results().getFirst().output()).doesNotContain("do-not-store");
        assertThat(new com.lanhao.ops.diagnosis.RecoveryPolicy().hasBlockingEvidence(workflow().analyze(result).context())).isTrue();
    }
    @Test void changedIdentityOrReplacedResourceNeverSuppliesOutputToDiagnosis() {
        when(identity.unchanged()).thenReturn(false);var result=workflow().execute(work());
        verifyNoInteractions(process);assertThat(workflow().analyze(result).veto()).isTrue();
        when(identity.unchanged()).thenReturn(true);when(scopes.resolve(scope)).thenReturn(catalog,new Catalog(List.of(),"已替换"));
        when(process.run(anyList(),any())).thenReturn(new BoundedProcess.Result("SUCCEEDED","must-discard",0));
        result=workflow().execute(work());assertThat(result.results().getFirst().output()).doesNotContain("must-discard");assertThat(result.results().getFirst().state()).isEqualTo("FAILED");
    }
    @Test void failedOrTimedOutCommandsVetoAutomaticRecovery() {
        when(identity.unchanged()).thenReturn(true);when(scopes.resolve(scope)).thenReturn(catalog);
        for(String state:List.of("FAILED","TIMED_OUT","OUTPUT_LIMIT")) {
            when(process.run(anyList(),any())).thenReturn(new BoundedProcess.Result(state,"incomplete",null));
            assertThat(workflow().analyze(workflow().execute(work())).veto()).as(state).isTrue();
        }
    }
}
