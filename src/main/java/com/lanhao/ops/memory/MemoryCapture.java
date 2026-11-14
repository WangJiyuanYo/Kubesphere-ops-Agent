package com.lanhao.ops.memory;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lanhao.ops.domain.Model.Run;
import com.lanhao.ops.persistence.SqlDialect;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class MemoryCapture {
    private final JdbcTemplate jdbc;
    private final SqlDialect db;
    private final MemoryStore memories;
    private final ObjectMapper json;
    private final String environment;
    public MemoryCapture(JdbcTemplate jdbc,MemoryStore memories,ObjectMapper json,com.lanhao.ops.AgentProperties config){this.jdbc=jdbc;this.db=new SqlDialect(jdbc);this.memories=memories;this.json=json;this.environment=config.mode().name();}
    @Scheduled(fixedDelayString="${agent.memory.capture-interval:5000}")
    public void capture() {
        var pending=jdbc.query(db.sql("SELECT run_id,payload@text@ FROM agent_memory_outbox WHERE processed_at IS NULL AND environment=? AND due_at<=@now@ ORDER BY due_at LIMIT 10"),
                (rs,i)->new String[]{rs.getString(1),rs.getString(2)},environment);
        for(String[] item:pending) {
            try {
                Run run=json.readValue(item[1],Run.class);
                if(run.state.terminal()&&!run.verifiedRecovery()) {
                    LearningOutbox.enqueue(jdbc,json,LearningOutbox.unsuccessfulRun(run));
                }else memories.capture(run);
                jdbc.update(db.sql("UPDATE agent_memory_outbox SET processed_at=@now@ WHERE run_id=? AND processed_at IS NULL"),run.id);
            }catch(Exception failure){
                // 每次失败推迟一分钟，避免一个坏记录阻塞后续成功案例。
                jdbc.update(db.sql("UPDATE agent_memory_outbox SET attempts=attempts+1,due_at=@afterSeconds@ WHERE run_id=? AND processed_at IS NULL"),60,java.util.UUID.fromString(item[0]));
                org.slf4j.LoggerFactory.getLogger(MemoryCapture.class).warn("排障记忆归档未完成，保留出队记录等待重试");
            }
        }
    }
}
