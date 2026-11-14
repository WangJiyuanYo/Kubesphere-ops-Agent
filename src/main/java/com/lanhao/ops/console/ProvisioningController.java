package com.lanhao.ops.console;

import com.lanhao.ops.persistence.RunStore;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.time.Instant;
import java.util.*;
import static com.lanhao.ops.console.ProvisioningModel.*;

@RestController
@RequestMapping("/api/console")
public class ProvisioningController {
    public record WatchView(Watch watch,Policy policy,String anomaly) {}
    public record Page(ProvisioningSync.Status source,List<WatchView> items) {}
    public record Detail(WatchView item,List<StageEvent> timeline,List<Trigger> triggers,com.lanhao.ops.domain.Model.Run recovery) {}
    public record PauseInput(@Min(0) long revision,boolean paused) {}
    private final ProvisioningStore watches;
    private final ProvisioningSync sync;
    private final AutonomyPolicies policies;
    private final ProvisioningAutonomy autonomy;
    private final RunStore runs;
    public ProvisioningController(ProvisioningStore watches,ProvisioningSync sync,AutonomyPolicies policies,ProvisioningAutonomy autonomy,RunStore runs) {
        this.watches=watches;this.sync=sync;this.policies=policies;this.autonomy=autonomy;this.runs=runs;
    }
    @GetMapping("/provisioning-watches") public Page list(@RequestParam(defaultValue="0") int offset){return new Page(sync.status(),watches.list(offset).stream().map(this::view).toList());}
    @PostMapping("/provisioning-watches/sync") public ProvisioningSync.Status sync(){return sync.sync();}
    @GetMapping("/provisioning-watches/{id}") public Detail get(@PathVariable UUID id){var watch=watches.get(id);return new Detail(view(watch),watches.timeline(id),autonomy.triggers(id),watch.runId()==null?null:runs.find(watch.runId()).orElse(null));}
    @PostMapping("/provisioning-watches/{id}/pause") public Watch pause(@PathVariable UUID id,@Valid @RequestBody PauseInput input,Principal actor){return watches.pause(id,input.revision(),input.paused(),actor.getName());}
    @GetMapping("/autonomy-policies") public List<Policy> policies(){return policies.list();}
    @PutMapping("/autonomy-policies/{id}") public Policy save(@PathVariable UUID id,@Valid @RequestBody PolicyInput input,Principal actor){return policies.save(id,input,actor.getName());}
    private WatchView view(Watch watch){var policy=policies.matching(watch).orElse(null);return new WatchView(watch,policy,policy==null?"UNMANAGED":watches.anomaly(watch,policy,Instant.now()));}
}
