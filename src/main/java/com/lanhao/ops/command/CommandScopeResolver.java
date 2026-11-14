package com.lanhao.ops.command;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.workflow.StepControl;
import io.fabric8.kubernetes.api.model.*;
import io.fabric8.kubernetes.client.*;
import io.fabric8.kubernetes.client.dsl.base.ResourceDefinitionContext;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;
import java.util.*;
import static com.lanhao.ops.command.CommandModel.*;

@Component
public class CommandScopeResolver {
    private final AgentProperties config;private final ObjectProvider<KubernetesClient> clients;
    public CommandScopeResolver(AgentProperties config,ObjectProvider<KubernetesClient> clients){this.config=config;this.clients=clients;}
    public Catalog resolve(Scope scope) {
        var subjects=new ArrayList<Subject>();
        try {
            for(Target target:scope.targets()) {
                StepControl.check();
                if(!java.time.Instant.now().isBefore(scope.deadline()))return new Catalog(List.of(),"命令采证阶段超时");
                if(!config.namespaces().contains(target.namespace()))return new Catalog(List.of(),"命名空间未授权");
                if(config.mode()==AgentProperties.Mode.DEMO) {
                    subjects.add(cr(target));continue;
                }
                KubernetesClient k=Objects.requireNonNull(clients.getIfAvailable());
                var type=new ResourceDefinitionContext.Builder().withGroup("platform.lanhao.io").withVersion("v1alpha1")
                        .withPlural(target.kind().plural).withNamespaced(true).build();
                var resource=k.genericKubernetesResources(type).inNamespace(target.namespace()).withName(target.name()).get();
                if(resource==null||!target.uid().equals(resource.getMetadata().getUid())||resource.getMetadata().getDeletionTimestamp()!=null)
                    return new Catalog(List.of(),"绑定资源身份已经变化");
                subjects.add(cr(target));Set<String> owners=new HashSet<>(Set.of(target.uid()));String ns=target.namespace();
                check(scope);expand(owners,k.apps().deployments().inNamespace(ns).list().getItems());
                check(scope);expand(owners,k.apps().statefulSets().inNamespace(ns).list().getItems());
                check(scope);expand(owners,k.batch().v1().jobs().inNamespace(ns).list().getItems());
                check(scope);expand(owners,k.apps().replicaSets().inNamespace(ns).list().getItems());
                check(scope);
                k.pods().inNamespace(ns).list().getItems().stream().filter(p->owned(p,owners)&&p.getMetadata().getDeletionTimestamp()==null)
                        .sorted(Comparator.comparing(p->p.getMetadata().getName())).limit(4).forEach(p->{
                            if(p.getSpec()!=null) {
                                var containers=new ArrayList<Container>();containers.addAll(p.getSpec().getContainers());
                                if(p.getSpec().getInitContainers()!=null)containers.addAll(p.getSpec().getInitContainers());
                                containers.stream().limit(4).forEach(c->subjects.add(new Subject(p.getMetadata().getUid()+":"+c.getName(),"pods",ns,
                                        p.getMetadata().getName(),p.getMetadata().getUid(),c.getName(),target.uid())));
                            }
                        });
            }
            check(scope);return new Catalog(List.copyOf(subjects),"");
        }catch(RuntimeException failure){StepControl.check();return new Catalog(List.of(),"资源目录采集失败，未授予命令执行权限");}
    }
    private void check(Scope scope){StepControl.check();if(!java.time.Instant.now().isBefore(scope.deadline()))throw new IllegalStateException("命令采证阶段超时");}
    private Subject cr(Target t){return new Subject(t.uid(),t.kind().plural+".platform.lanhao.io",t.namespace(),t.name(),t.uid(),"",t.uid());}
    private void expand(Set<String> owners,List<? extends HasMetadata> resources){for(var r:resources)if(owned(r,owners))owners.add(r.getMetadata().getUid());}
    private boolean owned(HasMetadata resource,Set<String> owners){return resource.getMetadata().getOwnerReferences()!=null
            &&resource.getMetadata().getOwnerReferences().stream().anyMatch(r->owners.contains(r.getUid()));}
}
