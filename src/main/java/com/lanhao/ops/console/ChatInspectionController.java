package com.lanhao.ops.console;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;

@RestController
@RequestMapping(value="/api/console/sessions/{sessionId}/turns/{turnId}/inspection",produces="application/json")
public class ChatInspectionController {
    public record View(boolean available,ChatInspectionGraph.Report report) {}
    private final ChatStore chats;
    private final ChatInspectionStore inspections;
    public ChatInspectionController(ChatStore chats,ChatInspectionStore inspections){this.chats=chats;this.inspections=inspections;}
    @GetMapping public View get(@PathVariable UUID sessionId,@PathVariable UUID turnId) {
        var turn=chats.turn(turnId);
        if(!sessionId.equals(turn.sessionId()))throw new ResponseStatusException(HttpStatus.NOT_FOUND,"消息不属于此会话");
        return inspections.find(turnId).map(report->{
            if(!inspections.active(turnId)&&report.stage().equals("RUNNING")) {
                var requests=report.requests().stream().map(r->r.state().equals("RUNNING")
                        ?new ChatInspectionGraph.Request(r.sequence(),r.operation(),r.request(),"UNKNOWN","执行中断或租约过期，结果未确认；不自动重放",r.at()):r).toList();
                report=new ChatInspectionGraph.Report(report.turnId(),report.plan(),"UNKNOWN",requests,report.evidence(),report.issues(),report.at());
            }
            return new View(true,report);
        }).orElse(new View(false,null));
    }
}
