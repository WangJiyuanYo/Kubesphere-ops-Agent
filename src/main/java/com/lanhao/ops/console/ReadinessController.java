package com.lanhao.ops.console;

import com.lanhao.ops.AgentProperties;
import com.lanhao.ops.OpsProperties;
import com.lanhao.ops.command.KubectlProperties;
import org.springframework.web.bind.annotation.*;
import java.time.Instant;
import java.util.*;

/** 配置与最近观测的说明；读取此接口不触发模型调用、扫描或恢复。 */
@RestController
@RequestMapping("/api/console/readiness")
public class ReadinessController {
    public record Check(String id,String label,String state,String detail,Instant observedAt) {}
    public record View(String mode,List<Check> checks,String note) {}
    private final AgentProperties config;
    private final OpsProperties ops;
    private final KubectlProperties cli;
    private final ConsoleSettings settings;
    private final DiscoveryStore discovery;
    private final PlatformCatalog catalog;
    public ReadinessController(AgentProperties config,OpsProperties ops,KubectlProperties cli,
            ConsoleSettings settings,DiscoveryStore discovery,PlatformCatalog catalog) {
        this.config=config;this.ops=ops;this.cli=cli;this.settings=settings;this.discovery=discovery;this.catalog=catalog;
    }
    @GetMapping public View view() {
        var checks=new ArrayList<Check>();
        var llm=settings.llmView();
        checks.add(new Check("llm","模型分析",llm.enabled()&&llm.hasApiKey()?"CONFIGURED":"RULES",
                llm.enabled()&&llm.hasApiKey()?"已配置；连通性请在 LLM 设置中测试。":"当前使用规则分析，可在 LLM 设置中启用模型。",null));
        var connections=settings.connections();
        var sources=discovery.sources().stream().filter(DiscoveryStore.Source::enabled).toList();
        int enabled=0;
        for(var connection:connections) {
            var source=sources.stream().filter(s->connection.id().equals(s.connectionId())).findFirst();
            if(source.isPresent()) {
                enabled++;var value=source.get();
                String state=value.checkedAt()==null?"UNCHECKED":value.error()!=null&&!value.error().isBlank()?"DEGRADED":"OBSERVED";
                checks.add(new Check("discovery:"+connection.id(),connection.name()+" · 自动发现",state,
                        "监控范围："+String.join("、",value.namespaces())+"。"+(state.equals("DEGRADED")?"上次扫描失败，请查看连接与监控详情。":state.equals("UNCHECKED")?"等待首次扫描。":"显示上次扫描记录，不代表当前连通性。"),value.checkedAt()));
            }else checks.add(new Check("discovery:"+connection.id(),connection.name()+" · 自动发现","DISABLED","连接已保存，尚未启用部署监控。",null));
        }
        checks.add(new Check("connections","集群连接",connections.isEmpty()?"UNCONFIGURED":"CONFIGURED",
                "已保存 "+connections.size()+" 个连接，启用 "+enabled+" 个监控。保存连接不会自动扩大监控范围。",null));
        var directory=catalog.view(0);
        checks.add(new Check("catalog","租户部署目录",!directory.enabled()?"DISABLED":!directory.warning().isBlank()?"DEGRADED":directory.checkedAt()==null?"UNCHECKED":"OBSERVED",
                !directory.enabled()?"未启用平台目录，平台创建 CR 前的开通阶段不会出现在目录中。":directory.warning().isBlank()?"已保存上次同步结果；关联项目时仍会重新鉴权与核对集群。":directory.warning(),directory.checkedAt()));
        boolean demo=config.mode()==AgentProperties.Mode.DEMO;
        checks.add(new Check("recovery","受控恢复",demo?"DEMO":"CONFIGURED",demo?
                "当前为演示恢复。页面保存的真实集群连接仍仅用于只读调查。":
                "使用独立执行身份；每次转交仍需平台身份、同集群目标、批准、预算及验收检查。",null));
        checks.add(new Check("verification","恢复验收目录",ops.verificationProfiles().isEmpty()?"UNCONFIGURED":"CONFIGURED",
                "本机已配置 "+ops.verificationProfiles().size()+" 个验收项；真实恢复还要求平台为目标项目配置相同验收档案。",null));
        checks.add(new Check("kubectl","kubectl 采证",cli.enabled()?demo?"DEMO":"CONFIGURED":"DISABLED",
                cli.enabled()?demo?"演示采证，不会运行真实 kubectl。":"已启用结构化白名单命令；执行时仍校验冻结身份和目标范围。":"当前使用 API 采证；未启用本机 kubectl。",null));
        return new View(config.mode().name(),List.copyOf(checks),"此处展示配置和历史观测，不是全链路验收结论。扫描失败不触发恢复；新任务仍由用户选择交给 Agent。");
    }
}
