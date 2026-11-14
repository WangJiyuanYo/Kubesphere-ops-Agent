package com.lanhao.ops.console;

import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.persistence.RunStore;
import org.springframework.stereotype.Component;
import java.util.UUID;

@Component
public class ChatContextGuard {
    private final ConsoleSettings settings;
    private final RunStore runs;
    private final DeploymentIdentity identity;
    private final DiscoveryStore discovery;

    public ChatContextGuard(ConsoleSettings settings, RunStore runs, DeploymentIdentity identity,DiscoveryStore discovery) {
        this.settings=settings; this.runs=runs; this.identity=identity;
        this.discovery=discovery;
    }

    public void validatePair(UUID connectionId, UUID runId) {
        if(connectionId==null||runId==null)return;
        var connection=settings.connection(connectionId);
        var run=runs.find(runId).orElseThrow(()->new ContextMismatch("关联任务不存在，请重新选择"));
        if(!identity.matches(run,connection.apiUrl(),connection.namespace()))
            throw new ContextMismatch("恢复任务与连接的环境、集群或命名空间不一致，不能混合证据；请选择匹配目标或仅关联任务");
    }

    public void validate(ChatStore.Session session) {
        if(session.connectionId()==null) {
            if(session.connectionOrigin()!=null)throw new ContextMismatch("原连接已删除，请新建对话；历史文本仍可查看");
            return;
        }
        var connection=connection(session);
        if(session.connectionOrigin()==null||!session.connectionOrigin().equals(DiscoveryGateway.origin(connection)))
            throw new ContextMismatch("会话目标已改变（集群或命名空间已变化），已停止采证；请新建对话，避免混入历史环境");
        validatePair(session.connectionId(),session.runId());
    }

    public String memoryScope(ChatStore.Session session) {
        validate(session);
        if(session.connectionId()!=null) {
            var connection=connection(session);
            return DeploymentIdentity.realScope(connection.apiUrl(),connection.namespace());
        }
        return session.runId()==null?"UNBOUND":runs.find(session.runId()).map(DeploymentIdentity::scope).orElse("UNBOUND");
    }
    public ConsoleSettings.Connection connection(ChatStore.Session session) {
        if(session.discoveryId()==null)return settings.connection(session.connectionId());
        var task=discovery.task(session.discoveryId());
        if(!java.util.Objects.equals(task.connectionId(),session.connectionId()))throw new ContextMismatch("发现任务与会话连接不一致");
        return discovery.connection(task);
    }
}
