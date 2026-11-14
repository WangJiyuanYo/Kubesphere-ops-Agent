package com.lanhao.ops.console;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/** 目录来源沿用受信平台地址，开关和扫描页数属于部署配置。 */
@Validated
@ConfigurationProperties("agent.platform-catalog")
public record PlatformCatalogProperties(@DefaultValue("false") boolean enabled,
        @DefaultValue("5") @Min(1) @Max(20) int maxPages,
        @DefaultValue("false") boolean allowLoopbackHttp) {
    @org.springframework.boot.context.properties.bind.ConstructorBinding public PlatformCatalogProperties {}
    public PlatformCatalogProperties(boolean enabled,int maxPages) { this(enabled,maxPages,false); }

    /** 本机联调仅接受明确的回环 IP，不解析 localhost 或其他主机名。 */
    public boolean accepts(java.net.URI value) {
        if(com.lanhao.ops.OpsProperties.secure(value))return !value.getPath().endsWith("/");
        return allowLoopbackHttp&&value!=null&&"http".equalsIgnoreCase(value.getScheme())
                &&("127.0.0.1".equals(value.getHost())||"[::1]".equals(value.getHost()))
                &&value.getUserInfo()==null&&value.getQuery()==null&&value.getFragment()==null
                &&!value.getPath().endsWith("/");
    }
}
