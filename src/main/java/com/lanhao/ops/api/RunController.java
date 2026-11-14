package com.lanhao.ops.api;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.domain.Model.*;
import com.lanhao.ops.persistence.RunStore;
import com.lanhao.ops.port.DeploymentPort;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.security.Principal;
import java.util.*;

@RestController
@RequestMapping("/api/runs")
public class RunController {
    private final RunStore store;
    private final AgentProperties config;
    private final DeploymentPort deployment;
    public RunController(RunStore store, AgentProperties config, DeploymentPort deployment) {
        this.store = store; this.config = config; this.deployment = deployment;
    }
    @PostMapping
    public ResponseEntity<Run> create(@Valid @RequestBody StartRequest request) {
        if (request.targets().stream().anyMatch(t -> !config.namespaces().contains(t.namespace())))
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "目标命名空间未授权");
        if (request.targets().stream().map(Target::uid).distinct().count() != request.targets().size()
                || request.targets().stream().map(t -> t.kind() + "/" + t.namespace() + "/" + t.name()).distinct().count() != request.targets().size())
            throw new IllegalArgumentException("部署目标不可重复");
        // 幂等重放返回最初账本，不能因目标后来变化而把合法重放变成新注册。
        if (store.findByRequestKey(request.requestKey()).isEmpty()) deployment.validateRegistration(request);
        return ResponseEntity.accepted().body(store.create(request));
    }
    @GetMapping
    public List<Run> list(@RequestParam(defaultValue = "20") int limit, @RequestParam(defaultValue = "0") int offset) {
        return store.list(limit, offset);
    }
    @GetMapping("/{id}")
    public Run get(@PathVariable UUID id) {
        return store.find(id).orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND));
    }
    public record Approval(@NotBlank String planId) {}
    @PostMapping("/{id}/approve")
    public Run approve(@PathVariable UUID id, @Valid @RequestBody Approval approval, Principal principal) {
        return store.approve(id, approval.planId(), principal.getName());
    }
    @PostMapping("/{id}/cancel")
    public Run cancel(@PathVariable UUID id, Principal principal) { return store.cancel(id, principal.getName()); }
}
