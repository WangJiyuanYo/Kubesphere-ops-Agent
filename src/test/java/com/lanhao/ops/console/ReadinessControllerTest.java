package com.lanhao.ops.console;

import com.lanhao.ops.*;
import com.lanhao.ops.command.KubectlProperties;
import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;

class ReadinessControllerTest {
    @Test void reportsConfigurationAndHistoricalObservationsWithoutClaimingLiveRecoveryReadiness() {
        var settings=mock(ConsoleSettings.class);var discovery=mock(DiscoveryStore.class);var catalog=mock(PlatformCatalog.class);
        var id=UUID.randomUUID();var checked=Instant.parse("2026-11-12T00:00:00Z");
        when(settings.llmView()).thenReturn(new ConsoleSettings.LlmView(1,true,ConsoleSettings.Provider.OPENAI_COMPATIBLE,"https://model.example","model",true,.1,512,true));
        when(settings.connections()).thenReturn(List.of(new ConsoleSettings.ConnectionView(id,1,"测试连接","https://cluster.example","","default",true,false)));
        when(discovery.sources()).thenReturn(List.of(new DiscoveryStore.Source(id,true,1,checked,"upstream-secret-not-for-summary",false,List.of("team-a"))));
        when(catalog.view(0)).thenReturn(new PlatformCatalog.View(false,"",null,List.of(),false));
        var ops=new OpsProperties(4,Duration.ofSeconds(75),Duration.ofSeconds(45),true,Map.of(),null);
        var result=new ReadinessController(TestFixtures.config(false),ops,new KubectlProperties(false,null,null,null,Duration.ofSeconds(2),2),settings,discovery,catalog).view();
        assertThat(result.checks()).anySatisfy(c->{assertThat(c.id()).isEqualTo("recovery");assertThat(c.state()).isEqualTo("DEMO");});
        assertThat(result.checks()).anySatisfy(c->{assertThat(c.id()).isEqualTo("discovery:"+id);assertThat(c.state()).isEqualTo("DEGRADED");assertThat(c.observedAt()).isEqualTo(checked);assertThat(c.detail()).contains("team-a").doesNotContain("upstream-secret");});
        assertThat(result.checks()).anySatisfy(c->{assertThat(c.id()).isEqualTo("llm");assertThat(c.state()).isEqualTo("CONFIGURED");assertThat(c.detail()).contains("连通性");});
        verify(settings).llmView();verify(settings).connections();verify(discovery).sources();verify(catalog).view(0);
        verifyNoMoreInteractions(settings,discovery,catalog);
    }
}
