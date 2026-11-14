package com.lanhao.ops.console;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import org.springframework.web.bind.annotation.*;
import java.util.UUID;

@RestController
@RequestMapping("/api/console/platform-catalog")
public class PlatformCatalogController {
    private final PlatformCatalog catalog;
    public PlatformCatalogController(PlatformCatalog catalog){this.catalog=catalog;}
    public record MonitorInput(@NotNull UUID connectionId) {}
    @GetMapping public PlatformCatalog.View view(@RequestParam(defaultValue="0") int offset){return catalog.view(offset);}
    @PostMapping("/sync") public PlatformCatalog.View sync(){return catalog.sync();}
    @GetMapping("/investigations/{id}") public java.util.List<PlatformCatalogGateway.Entry> matches(@PathVariable UUID id){return catalog.matches(id);}
    @PostMapping("/{id}/monitor") public PlatformCatalog.Mapping monitor(@PathVariable String id,@Valid @RequestBody MonitorInput input){return catalog.monitor(id,input.connectionId());}
}
