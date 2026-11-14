package com.lanhao.ops.console;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/console/discovery")
public class DiscoveryController {
    private final DiscoveryStore store;
    private final DiscoveryService service;
    private final ConsoleSettings settings;
    public DiscoveryController(DiscoveryStore store,DiscoveryService service,ConsoleSettings settings){this.store=store;this.service=service;this.settings=settings;}
    public record SourceInput(@Min(0) long revision,boolean enabled,@Size(min=1,max=20) List<@NotBlank @Size(max=63) String> namespaces) {}
    public record Selection(@Min(0) long revision,@NotNull UUID requestId) {}
    public record Ignore(@Min(0) long revision,boolean ignored) {}
    @GetMapping("/sources") public List<DiscoveryStore.Source> sources(){return store.sources();}
    @PutMapping("/sources/{id}") public DiscoveryStore.Source configure(@PathVariable UUID id,@Valid @RequestBody SourceInput input){return store.configure(id,input.enabled(),input.revision(),input.namespaces());}
    @PostMapping("/sources/{id}/sync") public DiscoveryStore.Source sync(@PathVariable UUID id){
        settings.connection(id);if(!store.source(id).enabled())throw new ConsoleFailure("请先启用此连接的部署发现");
        return service.scan(id,true);
    }
    @GetMapping("/tasks") public DiscoveryStore.Page tasks(@RequestParam(required=false) UUID connectionId,@RequestParam(required=false) DiscoveryStore.State state,@RequestParam(defaultValue="0") int offset){return store.list(connectionId,state,offset);}
    @GetMapping("/tasks/{id}") public DiscoveryStore.Task task(@PathVariable UUID id){return store.task(id);}
    @PostMapping("/tasks/{id}/select") public DiscoveryStore.Task select(@PathVariable UUID id,@Valid @RequestBody Selection input,Principal principal){return store.select(id,input.revision(),input.requestId(),principal.getName());}
    @PostMapping("/tasks/{id}/ignore") public DiscoveryStore.Task ignore(@PathVariable UUID id,@Valid @RequestBody Ignore input){return store.ignore(id,input.revision(),input.ignored());}
}
