package com.lanhao.ops.api;

import com.lanhao.ops.console.ProvisioningModel.*;
import com.lanhao.ops.console.ProvisioningStore;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/provisioning-stage-events/v1")
public class ProvisioningEventController {
    private final ProvisioningStore store;
    public ProvisioningEventController(ProvisioningStore store){this.store=store;}
    @PostMapping public ResponseEntity<Receipt> ingest(@Valid @RequestBody StageEvent event) {
        // API 认证授权接收事实；自动接管前还须用当前平台凭据重新核验授权和阶段。
        return ResponseEntity.accepted().body(store.ingest(event,null));
    }
}
