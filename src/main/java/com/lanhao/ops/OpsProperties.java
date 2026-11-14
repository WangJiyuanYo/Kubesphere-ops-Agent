package com.lanhao.ops;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.time.Duration;
import java.util.Map;

@Validated
@ConfigurationProperties("agent.ops")
public record OpsProperties(
        @DefaultValue("4") @Min(1) @Max(32) int workers,
        @DefaultValue("75s") Duration stepTimeout,
        @DefaultValue("45s") Duration collectionTimeout,
        @DefaultValue("true") boolean strictPlatformContract,
        Map<String, @Valid VerificationProfile> verificationProfiles,
        @DefaultValue @Valid Notification notification) {
    public OpsProperties {
        verificationProfiles = verificationProfiles == null ? Map.of() : Map.copyOf(verificationProfiles);
    }
    public record VerificationProfile(@NotNull URI url) {
        @AssertTrue(message = "验收目录必须使用无内嵌凭据的 HTTPS 地址")
        public boolean isValidUrl() { return secure(url); }
    }
    public record Notification(@DefaultValue("false") boolean enabled, URI url, String secret,
            @DefaultValue("8") @Min(1) @Max(20) int maxAttempts) {
        @AssertTrue(message = "通知需 HTTPS 地址及至少 32 字符签名密钥")
        public boolean isValidSettings() {
            return !enabled || secure(url) && secret != null && secret.length() >= 32;
        }
    }
    @AssertTrue(message = "单步时限需为 1–90 秒，采证时限需为正数且小于单步时限")
    public boolean isValidTiming() {
        return stepTimeout != null && collectionTimeout != null && stepTimeout.toMillis() >= 1000
                && stepTimeout.compareTo(Duration.ofSeconds(90)) <= 0
                && !collectionTimeout.isNegative() && !collectionTimeout.isZero()
                && collectionTimeout.compareTo(stepTimeout) < 0;
    }
    public static boolean secure(URI value) {
        return value != null && "https".equalsIgnoreCase(value.getScheme()) && value.getHost() != null
                && value.getUserInfo() == null && value.getQuery() == null && value.getFragment() == null;
    }
}
