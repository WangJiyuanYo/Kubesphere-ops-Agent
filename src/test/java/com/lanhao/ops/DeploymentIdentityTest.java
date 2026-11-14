package com.lanhao.ops;

import com.lanhao.ops.domain.DeploymentIdentity;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.port.DeploymentPort;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class DeploymentIdentityTest {
    @Test void platformAddressIsFrozenIndependentlyOfExecutionCluster() {
        var config=mock(AgentProperties.class);when(config.mode()).thenReturn(AgentProperties.Mode.KUBERNETES);
        when(config.platformBaseUrl()).thenReturn(java.net.URI.create("https://platform-one.test/api"));
        var port=mock(DeploymentPort.class);when(port.clusterApiUrl()).thenReturn("https://cluster.test");
        var identity=new DeploymentIdentity(config,port);var run=new Run(TestFixtures.request("platform-identity"),Instant.now());identity.stamp(run);
        when(config.platformBaseUrl()).thenReturn(java.net.URI.create("https://platform-two.test/api"));
        assertThatThrownBy(()->identity.assertCurrent(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    @Test void livePairRequiresFrozenClusterEnvironmentAndNamespace() {
        var config=mock(AgentProperties.class);when(config.mode()).thenReturn(AgentProperties.Mode.KUBERNETES);
        var port=mock(DeploymentPort.class);when(port.clusterApiUrl()).thenReturn("https://API.example.com:443/");
        var identity=new DeploymentIdentity(config,port);
        var run=new Run(TestFixtures.request("identity"),Instant.now());
        identity.stamp(run);
        assertThat(identity.matches(run,"https://api.example.com","agent-demo")).isTrue();
        assertThat(identity.matches(run,"https://other.example.com","agent-demo")).isFalse();
        assertThat(identity.matches(run,"https://api.example.com","other-ns")).isFalse();
        when(port.clusterApiUrl()).thenReturn("https://other.example.com");
        assertThat(identity.matches(run,"https://api.example.com","agent-demo")).isFalse();
        assertThatThrownBy(()->identity.assertCurrent(run)).isInstanceOf(DeploymentPort.Superseded.class);
    }
    @Test void legacyAndDemoNeverAuthorizeRealConnectionAndScopesRemainDistinct() {
        var config=TestFixtures.config(true);var port=mock(DeploymentPort.class);
        var identity=new DeploymentIdentity(config,port);
        var run=new Run(TestFixtures.request("legacy"),Instant.now());
        assertThat(DeploymentIdentity.scope(run)).isEqualTo("UNBOUND");
        identity.stamp(run);
        assertThat(DeploymentIdentity.scope(run)).isEqualTo("DEMO");
        assertThat(identity.matches(run,"https://api.example.com","agent-demo")).isFalse();
        assertThat(DeploymentIdentity.realScope("https://api.example.com/","a"))
                .isEqualTo(DeploymentIdentity.realScope("https://API.example.com:443","a"))
                .isNotEqualTo(DeploymentIdentity.realScope("https://api.example.com","b"));
    }
}
