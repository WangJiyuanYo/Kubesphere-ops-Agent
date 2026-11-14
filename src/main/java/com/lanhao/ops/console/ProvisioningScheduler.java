package com.lanhao.ops.console;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class ProvisioningScheduler {
    private final ProvisioningSync sync;
    private final ProvisioningAutonomy autonomy;
    public ProvisioningScheduler(ProvisioningSync sync,ProvisioningAutonomy autonomy){this.sync=sync;this.autonomy=autonomy;}
    @Scheduled(fixedDelayString="${agent.provisioning.poll-interval:30000}")
    public void poll(){try{sync.sync();autonomy.tick();}catch(RuntimeException failure){org.slf4j.LoggerFactory.getLogger(getClass()).warn("站点托管调度未完成，异常类型 {}",failure.getClass().getSimpleName());}}
}
