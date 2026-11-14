package com.lanhao.ops.command;

import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.RunStore;
import java.time.Instant;
import java.util.*;
import java.nio.charset.StandardCharsets;

public final class CommandModel {
    private CommandModel(){}
    public record Scope(UUID batchId,UUID runId,String leaseOwner,List<Target> targets,Instant deadline) {
        public static Scope of(Run run,RunStore.Claim claim){
            String key=run.id+":"+run.evidence.observedAt();
            return new Scope(UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8)),run.id,claim.owner(),List.copyOf(run.targets),Instant.now().plusSeconds(45));
        }
    }
    public record Subject(String id,String resource,String namespace,String name,String uid,String container,String rootUid){}
    public record Catalog(List<Subject> subjects,String issue){}
    // 模型只能选择目录中的身份和操作，不接收 argv、flags、路径、集群或 shell 字符串。
    public record Proposal(String operation,String subjectId,String purpose){}
    public record Review(Proposal proposal,Subject subject,boolean allowed,String reason,List<String> argv){}
    public record Execution(UUID id,Review review,String state,String output,Integer exitCode,Instant at){}
    public record Work(Scope scope,Catalog catalog,List<Proposal> proposals,List<Review> reviews,List<Execution> results,
            String source,String issue,boolean replay){}
    public record Analysis(String context,boolean veto){}
}
