package com.lanhao.ops.domain;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.domain.Model.Run;
import com.lanhao.ops.port.DeploymentPort;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.stream.Collectors;

/** 来源身份与运行进度分离；更换进程模式或执行集群不能接管旧环境的动作。 */
@Component
public class DeploymentIdentity {
    private final AgentProperties config;
    private final DeploymentPort port;

    public DeploymentIdentity(AgentProperties config, DeploymentPort port) {
        this.config = config;
        this.port = port;
    }

    public void stamp(Run run) {
        run.environment = config.mode().name();
        run.clusterApiUrl = config.mode() == AgentProperties.Mode.KUBERNETES ? endpoint(port.clusterApiUrl()) : "";
        run.platformApiUrl = config.platformBaseUrl()==null||config.platformBaseUrl().toString().isBlank()?"":endpoint(config.platformBaseUrl().toString());
    }

    public void assertCurrent(Run run) {
        // 老的模拟账本可以继续演示；无来源的旧任务绝不取得真实执行权限。
        if (run.environment == null && config.mode() == AgentProperties.Mode.DEMO) return;
        if (!Objects.equals(run.environment, config.mode().name())
                || config.mode() == AgentProperties.Mode.KUBERNETES
                && (run.clusterApiUrl == null || !endpoint(run.clusterApiUrl).equals(endpoint(port.clusterApiUrl()))
                ||!Objects.equals(run.platformApiUrl,config.platformBaseUrl()==null||config.platformBaseUrl().toString().isBlank()?"":endpoint(config.platformBaseUrl().toString()))))
            throw new DeploymentPort.Superseded("任务来源环境或执行集群已变化，停止旧任务，请核验后重新注册");
    }

    public boolean matches(Run run, String apiUrl, String namespace) {
        try { assertCurrent(run); }
        catch (DeploymentPort.Superseded changed) { return false; }
        return "KUBERNETES".equals(run.environment) && run.clusterApiUrl != null
                && endpoint(run.clusterApiUrl).equals(endpoint(apiUrl)) && !run.targets.isEmpty()
                && run.targets.stream().allMatch(target -> namespace.equals(target.namespace()));
    }

    public static String scope(Run run) {
        if ("DEMO".equals(run.environment)) return "DEMO";
        if (!"KUBERNETES".equals(run.environment) || run.clusterApiUrl == null || run.targets.isEmpty()) return "UNBOUND";
        String namespaces = run.targets.stream().map(Model.Target::namespace).distinct().sorted().collect(Collectors.joining(","));
        return realScope(run.clusterApiUrl, namespaces);
    }

    public static String realScope(String apiUrl, String namespace) {
        try {
            return "KUBERNETES:" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest((endpoint(apiUrl) + "\n" + namespace).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }

    public static String endpoint(String url) {
        if (url == null || url.isBlank()) throw new IllegalStateException("执行集群缺少明确 API 身份");
        URI uri = URI.create(url).normalize();
        try {
            int port = uri.getPort();
            if (port == 443 && "https".equalsIgnoreCase(uri.getScheme()) || port == 80 && "http".equalsIgnoreCase(uri.getScheme())) port = -1;
            return new URI(uri.getScheme().toLowerCase(java.util.Locale.ROOT), null,
                    uri.getHost().toLowerCase(java.util.Locale.ROOT), port, uri.getPath(), null, null).toASCIIString().replaceAll("/+$", "");
        } catch (java.net.URISyntaxException failure) { throw new IllegalArgumentException("集群 API 身份无效"); }
    }
}
