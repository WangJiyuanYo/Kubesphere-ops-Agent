package com.lanhao.ops.console;

import com.lanhao.ops.domain.Model.*;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import java.security.Principal;
import java.util.UUID;

@RestController
@RequestMapping("/api/console/discovery/tasks/{id}")
public class DeploymentCaseController {
    private final DeploymentCaseService cases;
    public DeploymentCaseController(DeploymentCaseService cases){this.cases=cases;}
    @GetMapping("/case") public DeploymentCaseService.CaseView view(@PathVariable UUID id){return cases.view(id);}
    @PostMapping("/platform-registration") public StartRequest resolve(@PathVariable UUID id,@Valid @RequestBody DeploymentCaseService.PlatformInput input){return cases.resolve(id,input);}
    @PostMapping("/recovery-preview") public StartRequest preview(@PathVariable UUID id,@Valid @RequestBody DeploymentCaseService.RecoveryInput input){return cases.preview(id,input);}
    @PostMapping("/recovery") public Run handoff(@PathVariable UUID id,@Valid @RequestBody DeploymentCaseService.RecoveryInput input,Principal principal){return cases.handoff(id,input,principal.getName());}
}
