package com.lanhao.ops;

import com.lanhao.ops.command.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Duration;
import static org.assertj.core.api.Assertions.*;

class KubectlIdentityTest {
    @TempDir Path folder;
    static AgentProperties real(){var d=TestFixtures.config(false);return new AgentProperties(AgentProperties.Mode.KUBERNETES,false,false,d.maxAttempts(),d.stallTimeout(),d.runTimeout(),d.retryBackoff(),d.leaseDuration(),d.verificationTimeout(),d.stableChecks(),d.namespaces(),d.apiUsername(),d.apiPassword(),null,null,null);}
    String content(boolean insecure,String user){return """
            apiVersion: v1
            kind: Config
            clusters:
              - name: cluster
                cluster:
                  server: https://kubernetes.example.com
                  insecure-skip-tls-verify: %s
            contexts:
              - name: readonly
                context:
                  cluster: cluster
                  user: service
            users:
              - name: service
                user:
            %s
            current-context: readonly
            """.formatted(insecure,user);}
    KubectlProperties cli(String context)throws Exception {
        Path binary=folder.resolve("kubectl.exe");Files.writeString(binary,"test-only placeholder; not executed");
        return new KubectlProperties(true,binary.toString(),folder.resolve("config").toString(),context,Duration.ofSeconds(5),3);
    }
    @Test void freezesExplicitClusterAndDetectsConfigReplacement()throws Exception {
        Files.writeString(folder.resolve("config"),content(false,"      token: fixture-only"));
        var identity=new KubectlIdentity(cli("readonly"),real());
        assertThat(identity.clientConfig().getMasterUrl()).startsWith("https://kubernetes.example.com");assertThat(identity.unchanged()).isTrue();
        Files.writeString(folder.resolve("config"),content(false,"      token: replacement"));assertThat(identity.unchanged()).isFalse();
    }
    @Test void rejectsMissingContextInsecureTlsAndCredentialPlugins()throws Exception {
        Files.writeString(folder.resolve("config"),content(false,"      token: fixture-only"));
        var missing=cli("missing");assertThatThrownBy(()->new KubectlIdentity(missing,real())).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(folder.resolve("config"),content(true,"      token: fixture-only"));var cli=cli("readonly");
        assertThatThrownBy(()->new KubectlIdentity(cli,real())).isInstanceOf(IllegalArgumentException.class);
        Files.writeString(folder.resolve("config"),content(false,"      exec:\n        command: arbitrary-helper\n        apiVersion: client.authentication.k8s.io/v1"));
        assertThatThrownBy(()->new KubectlIdentity(cli,real())).isInstanceOf(IllegalArgumentException.class).hasMessageContaining("插件");
    }
}
