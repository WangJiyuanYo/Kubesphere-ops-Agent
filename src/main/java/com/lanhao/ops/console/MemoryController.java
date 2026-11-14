package com.lanhao.ops.console;

import com.lanhao.ops.memory.*;
import com.lanhao.ops.persistence.SqlDialect;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.*;
import java.util.*;

@RestController
@RequestMapping("/api/console/memories")
public class MemoryController {
    private final MemoryStore store;
    private final ChatStore chats;
    private final SqlDialect db;
    private final ChatContextGuard contextGuard;
    private final JdbcTemplate jdbc;
    public MemoryController(MemoryStore store,ChatStore chats,JdbcTemplate jdbc,ChatContextGuard contextGuard){this.store=store;this.chats=chats;this.db=new SqlDialect(jdbc);this.contextGuard=contextGuard;this.jdbc=jdbc;}
    public record Input(@NotNull UUID requestId,@Min(0) long revision,@NotBlank @Size(max=120) String title,
            @NotBlank @Size(max=4000) String problem,@Size(max=4000) String cause,@NotBlank @Size(max=4000) String solution,
            @Size(max=4000) String verification,@Size(max=300) String tags,@NotNull MemoryStore.Status status,UUID sourceTurnId) {
        MemoryStore.Input content(){return new MemoryStore.Input(title,problem,cause,solution,verification,tags,status);}
    }
    @GetMapping public Map<String,Object> list(@RequestParam(defaultValue="") String q,@RequestParam(required=false) MemoryStore.Status status) {
        if(q.length()>500)throw new IllegalArgumentException("搜索词最多 500 字符");
        return Map.of("storage",db.sqlite()?"SQLITE":"POSTGRESQL","memorySearch","SQLite FTS5 · 中文双字组 / 技术词",
                "items",store.search(q,status,50));
    }
    @GetMapping("/{id}") public MemoryStore.Entry detail(@PathVariable UUID id){return store.get(id);}
    public record FeedbackInput(@NotNull UUID requestId,@NotNull UUID turnId,@Min(1) long revision,
            @NotNull MemoryStore.FeedbackKind kind,@Size(max=500) String note) {}
    @GetMapping("/feedback/turns/{turnId}") public List<MemoryStore.Feedback> feedback(@PathVariable UUID turnId) {
        chats.turn(turnId);return store.feedbackForTurn(turnId);
    }
    @PostMapping("/{id}/feedback") public MemoryStore.Feedback feedback(@PathVariable UUID id,@Valid @RequestBody FeedbackInput input) {
        var turn=chats.turn(input.turnId());
        if(!"SUCCEEDED".equals(turn.state())||turn.memories().stream().noneMatch(r->r.id().equals(id)&&r.revision()==input.revision()))
            throw new IllegalArgumentException("只能反馈本轮成功回复实际引用的记忆版本；旧记录请重新调查");
        return store.feedback(input.requestId(),id,input.revision(),turn.id(),contextGuard.memoryScope(chats.session(turn.sessionId())),input.kind(),input.note());
    }
    @GetMapping("/reflections/{turnId}") public Map<String,Object> reflection(@PathVariable UUID turnId) {
        chats.turn(turnId);
        var entry=store.reflection(turnId);
        if(entry.isPresent())return Map.of("state","READY","entry",entry.get());
        var jobs=jdbc.queryForList("SELECT attempts FROM agent_learning_outbox WHERE source_key=?","REFLECTION:CHAT:"+turnId);
        return Map.of("state",jobs.isEmpty()?"NOT_CREATED":"PENDING");
    }
    @PostMapping public MemoryStore.Entry create(@Valid @RequestBody Input input) {
        if(input.revision()!=0||input.status()==MemoryStore.Status.ARCHIVED)throw new IllegalArgumentException("新记忆状态无效");
        String sourceType="MANUAL",sourceRef="",sourceKey="MANUAL:"+input.requestId();
        if(input.sourceTurnId()!=null) {
            var turn=chats.turn(input.sourceTurnId());
            if(!"SUCCEEDED".equals(turn.state()))throw new IllegalArgumentException("只能从已完成的对话记录排查思路");
            sourceType="CHAT";sourceRef=turn.id().toString();sourceKey="CHAT:"+turn.id();
            return store.create(sourceKey,sourceType,sourceRef,input.content(),contextGuard.memoryScope(chats.session(turn.sessionId())));
        }
        return store.create(sourceKey,sourceType,sourceRef,input.content());
    }
    @PutMapping("/{id}") public MemoryStore.Entry update(@PathVariable UUID id,@Valid @RequestBody Input input){
        return store.update(id,input.revision(),input.content());
    }
}
