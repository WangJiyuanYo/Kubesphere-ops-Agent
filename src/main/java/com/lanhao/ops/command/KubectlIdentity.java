package com.lanhao.ops.command;

import com.lanhao.ops.AgentProperties;
import io.fabric8.kubernetes.client.Config;
import io.fabric8.kubernetes.client.utils.Serialization;
import org.springframework.stereotype.Component;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Fabric8 与 kubectl 共用启动时冻结的身份，模型和页面连接均不能切换身份。 */
@Component
public class KubectlIdentity {
    private final Path file;private final byte[] content;private final Config clientConfig;
    public KubectlIdentity(KubectlProperties cli,AgentProperties config)throws Exception {
        if(!cli.enabled()||config.mode()==AgentProperties.Mode.DEMO){file=null;content=null;clientConfig=null;return;}
        cli.validateNative();file=Path.of(cli.kubeconfig());content=Files.readAllBytes(file);
        String yaml=new String(content,StandardCharsets.UTF_8);
        var kube=Serialization.unmarshal(yaml,io.fabric8.kubernetes.api.model.Config.class);
        if(kube==null||kube.getContexts()==null||kube.getContexts().stream().noneMatch(c->cli.context().equals(c.getName())))
            throw new IllegalArgumentException("显式指定的 kubectl context 不存在");
        if(kube.getUsers()!=null&&kube.getUsers().stream().anyMatch(u->u.getUser()!=null&&(u.getUser().getExec()!=null||u.getUser().getAuthProvider()!=null)))
            throw new IllegalArgumentException("kubectl 专用 kubeconfig 不允许 exec 或 auth-provider 插件，使用只读服务账号或客户端证书");
        clientConfig=Config.fromKubeconfig(cli.context(),yaml,cli.kubeconfig());
        if(clientConfig.isTrustCerts()||clientConfig.getMasterUrl()==null||!clientConfig.getMasterUrl().startsWith("https://"))
            throw new IllegalArgumentException("kubectl 集群须使用 HTTPS 并验证证书");
    }
    public Config clientConfig(){return Objects.requireNonNull(clientConfig);}
    public boolean unchanged(){try{return file==null||Arrays.equals(content,Files.readAllBytes(file));}catch(Exception unavailable){return false;}}
}
