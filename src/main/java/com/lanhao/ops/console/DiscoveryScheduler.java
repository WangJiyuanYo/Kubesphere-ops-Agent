package com.lanhao.ops.console;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class DiscoveryScheduler {
    private final DiscoveryStore store;
    private final DiscoveryService service;
    public DiscoveryScheduler(DiscoveryStore store,DiscoveryService service){this.store=store;this.service=service;}
    @Scheduled(fixedDelayString="${agent.discovery.poll-interval:30000}")
    public void poll() {
        for(var source:store.sources())if(source.enabled()) {
            try{service.scan(source.connectionId(),false);}
            catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("部署发现未完成，连接 {}，异常类型 {}",source.connectionId(),failure.getClass().getSimpleName());}
        }
    }
    @Scheduled(fixedDelayString="${agent.discovery.process-interval:2000}")
    public void process() {
        try{service.processNext();}
        catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("发现任务处理未完成，异常类型 {}",failure.getClass().getSimpleName());}
    }
}
