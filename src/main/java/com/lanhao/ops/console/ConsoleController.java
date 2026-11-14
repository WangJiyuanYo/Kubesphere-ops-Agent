package com.lanhao.ops.console;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.persistence.RunStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.ai.chat.messages.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/console")
public class ConsoleController {
    private final ConsoleSettings settings;
    private final ModelGateway models;
    private final ModelCatalog modelCatalog;
    private final DiscoveryStore discovery;
    private final KubeSphereGateway clusters;
    private final ChatStore chats;
    private final AgentChat agent;
    private final RunStore runs;
    private final AgentProperties config;
    private final com.lanhao.ops.evidence.Redactor redactor;
    private final ChatContextGuard contextGuard;
    public ConsoleController(ConsoleSettings settings,ModelGateway models,KubeSphereGateway clusters,ChatStore chats,
            AgentChat agent,RunStore runs,AgentProperties config,com.lanhao.ops.evidence.Redactor redactor,ModelCatalog modelCatalog,DiscoveryStore discovery,ChatContextGuard contextGuard) {
        this.settings=settings;this.models=models;this.clusters=clusters;this.chats=chats;this.agent=agent;this.runs=runs;this.config=config;
        this.redactor=redactor;
        this.modelCatalog=modelCatalog;
        this.discovery=discovery;
        this.contextGuard=contextGuard;
    }
    @GetMapping("/bootstrap") public Map<String,Object> bootstrap(Principal principal) {
        return Map.of("username",principal.getName(),"mode",config.mode(),"llm",settings.llmView(),
                "connections",settings.connections(),"sessions",chats.sessions(),"runs",runs.list(30,0));
    }
    @GetMapping("/llm") public ConsoleSettings.LlmView llm(){return settings.llmView();}
    @PutMapping("/llm") public ConsoleSettings.LlmView saveLlm(@Valid @RequestBody ConsoleSettings.LlmInput input){return settings.saveLlm(input);}
    @PostMapping("/llm/models") public ModelCatalog.Result modelList(@Valid @RequestBody ConsoleSettings.ModelCatalogInput input){
        return modelCatalog.list(input);
    }
    @PostMapping("/llm/test") public Map<String,String> testLlm(){
        var llm=settings.llm().orElseThrow(()->new IllegalArgumentException("请先保存模型配置"));
        models.complete(llm,List.of(new SystemMessage("这是模型连接测试，请简短回复连接成功。"),new UserMessage("你好")));
        return Map.of("message","模型连接成功","model",llm.model());
    }
    @GetMapping("/connections") public List<ConsoleSettings.ConnectionView> connections(){return settings.connections();}
    @PostMapping("/connections") public ConsoleSettings.ConnectionView createConnection(@Valid @RequestBody ConsoleSettings.ConnectionInput input){
        if(input.revision()!=0) throw new IllegalArgumentException("新增连接版本应为 0");
        return settings.saveConnection(UUID.randomUUID(),input);
    }
    @PutMapping("/connections/{id}") public ConsoleSettings.ConnectionView saveConnection(@PathVariable UUID id,@Valid @RequestBody ConsoleSettings.ConnectionInput input){
        settings.connection(id);return settings.saveConnection(id,input);
    }
    @DeleteMapping("/connections/{id}") public void deleteConnection(@PathVariable UUID id,@RequestParam long revision){settings.deleteConnection(id,revision);}
    @PostMapping("/connections/{id}/test") public KubeSphereGateway.Snapshot testConnection(@PathVariable UUID id){return clusters.inspect(settings.connection(id));}
    public record SessionInput(@NotBlank @Size(max=80) String title,UUID connectionId,UUID runId,UUID discoveryId){}
    @PostMapping("/sessions") public ChatStore.Session createSession(@Valid @RequestBody SessionInput input){
        if(input.discoveryId()!=null) {
            if(input.runId()!=null)throw new ConsoleFailure("对话只能关联一个部署任务");
            var task=discovery.task(input.discoveryId());
            if(!Objects.equals(task.connectionId(),input.connectionId()))throw new ConsoleFailure("发现任务与对话连接不一致");
            discovery.connection(task);
        }
        if(input.connectionId()!=null) settings.connection(input.connectionId());
        if(input.runId()!=null&&runs.find(input.runId()).isEmpty()) throw new IllegalArgumentException("部署任务不存在");
        contextGuard.validatePair(input.connectionId(),input.runId());
        String title=redactor.clean(input.title().trim());
        return chats.create(title.substring(0,Math.min(title.length(),80)),input.connectionId(),input.runId(),input.discoveryId());
    }
    @GetMapping("/sessions") public List<ChatStore.Session> sessions(){return chats.sessions();}
    @GetMapping("/sessions/{id}/turns") public List<ChatStore.Turn> turns(@PathVariable UUID id){return chats.turns(id);}
    public record MessageInput(@NotNull UUID requestId,@NotBlank @Size(max=4000) String content,boolean includeCluster){}
    @PostMapping("/sessions/{id}/messages") public ChatStore.Turn message(@PathVariable UUID id,@Valid @RequestBody MessageInput input){
        return agent.send(id,input.requestId(),input.content(),input.includeCluster());
    }
}
