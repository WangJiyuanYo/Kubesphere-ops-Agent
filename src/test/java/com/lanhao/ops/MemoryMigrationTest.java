package com.lanhao.ops;

import com.lanhao.ops.memory.*;
import com.lanhao.ops.persistence.SqliteConfiguration;
import com.lanhao.ops.evidence.Redactor;
import java.nio.file.Files;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import static org.assertj.core.api.Assertions.*;

class MemoryMigrationTest {
    @Test void upgradesRealV2DatabaseWithoutOverwritingHumanRevisionAndIsIdempotent()throws Exception {
        var path=Files.createTempDirectory("agent-memory-upgrade-").resolve("memory.db");
        try(var source=SqliteConfiguration.open(path,"memory-upgrade")) {
            Flyway.configure().dataSource(source).locations("classpath:db/memory").table("agent_memory_schema_history")
                    .target("2").load().migrate();
            var jdbc=new JdbcTemplate(source);
            for(String kind:new String[]{"automatic","human","archived"})jdbc.update(
                    "INSERT INTO agent_memory(id,source_key,title,problem,cause,solution,verification,tags,status,environment,source_type,source_ref,revision,title_terms,body_terms,created_at,updated_at,scope_key) VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    UUID.randomUUID().toString(),kind,kind,"timeout","假设","检查重试","旧验收依据","timeout",kind.equals("archived")?"ARCHIVED":"VERIFIED","DEMO","RUN",UUID.randomUUID().toString(),kind.equals("human")?2:1,"timeout","timeout",1,1,"DEMO");
            var store=new MemoryStore(jdbc,new Redactor(),TestFixtures.config(false),path.toString());
            try {
                assertThat(jdbc.queryForObject("SELECT status FROM agent_memory WHERE source_key='automatic'",String.class)).isEqualTo("DRAFT");
                assertThat(jdbc.queryForObject("SELECT revision FROM agent_memory WHERE source_key='automatic'",Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT verification FROM agent_memory WHERE source_key='automatic'",String.class)).contains("动作受理证明","旧验收依据");
                assertThat(jdbc.queryForObject("SELECT status FROM agent_memory WHERE source_key='human'",String.class)).isEqualTo("VERIFIED");
                assertThat(jdbc.queryForObject("SELECT status FROM agent_memory WHERE source_key='archived'",String.class)).isEqualTo("ARCHIVED");
                assertThat(store.search("timeout",MemoryStore.Status.VERIFIED,10,"DEMO")).hasSize(1);
            }finally{store.close();}
            var restored=new MemoryStore(jdbc,new Redactor(),TestFixtures.config(false),path.toString());
            try {
                assertThat(jdbc.queryForObject("SELECT revision FROM agent_memory WHERE source_key='automatic'",Integer.class)).isEqualTo(2);
                assertThat(jdbc.queryForObject("SELECT count(*) FROM agent_memory",Integer.class)).isEqualTo(3);
            }finally{restored.close();}
        }
    }
}
