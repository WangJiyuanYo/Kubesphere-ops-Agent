package com.lanhao.ops.command;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.evidence.Redactor;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.lanhao.ops.command.CommandModel.*;

@Component
public class CommandSafety {
    private final AgentProperties config;private final KubectlProperties cli;private final Redactor redactor;
    public CommandSafety(AgentProperties config,KubectlProperties cli,Redactor redactor){this.config=config;this.cli=cli;this.redactor=redactor;}
    public Review review(Proposal input,Catalog catalog) {
        Proposal p=input==null?new Proposal("","",""):new Proposal(safe(input.operation()),safe(input.subjectId()),safe(input.purpose()));
        if(!catalog.issue().isBlank())return deny(p,catalog.issue());
        if(input==null||input.operation()==null||!Set.of("GET","DESCRIBE","EVENTS","LOGS").contains(input.operation()))return deny(p,"操作不在只读白名单内");
        Subject s=catalog.subjects().stream().filter(x->x.id().equals(input.subjectId())).findFirst().orElse(null);
        if(s==null||!config.namespaces().contains(s.namespace()))return deny(p,"资源不属于本任务受权目录");
        if(!s.namespace().matches("[a-z0-9]([-a-z0-9]*[a-z0-9])?")||!s.name().matches("[a-z0-9]([-a-z0-9.]*[a-z0-9])?")
                ||!s.uid().matches("[A-Za-z0-9-]{1,100}")||!s.container().matches("[a-z0-9-]*"))return deny(p,"资源标识格式无效");
        if(!s.resource().equals("pods")&&Arrays.stream(com.lanhao.ops.domain.Model.Kind.values()).noneMatch(k->s.resource().equals(k.plural+".platform.lanhao.io")))return deny(p,"资源类型不允许");
        if("LOGS".equals(p.operation())&&(!s.resource().equals("pods")||s.container().isBlank()))return deny(p,"日志只能读取已绑定 Pod 的明确容器");
        List<String> argv=new ArrayList<>();argv.add(config.mode()==AgentProperties.Mode.DEMO?"kubectl":cli.executable());
        if(config.mode()==AgentProperties.Mode.KUBERNETES){argv.add("--kubeconfig="+cli.kubeconfig());argv.add("--context="+cli.context());}
        argv.add("--namespace="+s.namespace());argv.add("--request-timeout=4s");
        switch(p.operation()) {
            case "GET"->{argv.addAll(List.of("get",s.resource(),s.name(),"-o=json"));}
            case "DESCRIBE"->argv.addAll(List.of("describe",s.resource(),s.name(),"--show-events=false"));
            case "EVENTS"->argv.addAll(List.of("get","events","--field-selector=involvedObject.uid="+s.uid(),"--chunk-size=20","-o=json"));
            case "LOGS"->argv.addAll(List.of("logs",s.name(),"--container="+s.container(),"--tail=60","--limit-bytes=4096"));
            default->throw new IllegalStateException();
        }
        return new Review(p,s,true,"只读白名单、任务归属及命名空间检查通过",List.copyOf(argv));
    }
    public Review deny(Proposal p,String reason){return new Review(p,null,false,reason,List.of());}
    private String safe(String input){String value=redactor.clean(Objects.toString(input,""));return value.substring(0,Math.min(value.length(),300));}
}
