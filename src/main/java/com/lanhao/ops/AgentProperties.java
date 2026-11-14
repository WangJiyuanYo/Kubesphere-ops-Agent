package com.lanhao.ops;

import jakarta.validation.constraints.*;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;
import java.net.URI;
import java.time.Duration;
import java.util.List;

@Validated
@ConfigurationProperties("agent")
public record AgentProperties(
        @NotNull Mode mode, boolean aiEnabled, boolean autoRetry,
        @Min(1) @Max(3) int maxAttempts,
        @NotNull Duration stallTimeout, @NotNull Duration runTimeout,
        @NotNull Duration retryBackoff, @NotNull Duration leaseDuration,
        @NotNull Duration verificationTimeout, @Min(2) int stableChecks,
        @NotEmpty List<@NotBlank @Size(max = 63) @Pattern(regexp = "[a-z0-9]([-a-z0-9]*[a-z0-9])?") String> namespaces,
        @NotBlank String apiUsername, @NotBlank String apiPassword,
        URI platformBaseUrl, String platformToken, URI verificationUrl) {
    public enum Mode { DEMO, KUBERNETES }

    @AssertTrue(message = "等待、退避和验证时限必须为正数，执行租约至少为 120 秒")
    public boolean isTimingValid() {
        if (stallTimeout == null || runTimeout == null || retryBackoff == null
                || leaseDuration == null || verificationTimeout == null) return true;
        return positive(stallTimeout) && positive(runTimeout) && positive(retryBackoff)
                && positive(verificationTimeout) && leaseDuration.compareTo(Duration.ofSeconds(120)) >= 0;
    }

    @AssertTrue(message = "真实模式需要独立 API 密码、平台凭据及无内嵌凭据的 HTTPS 平台和验收地址")
    public boolean isLiveSettingsValid() {
        if (mode != Mode.KUBERNETES) return true;
        return apiPassword != null && !"demo-local-only".equals(apiPassword) && apiPassword.length() >= 16
                && apiPassword.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= 72
                && platformToken != null && !platformToken.isBlank()
                && secureUri(platformBaseUrl) && !platformBaseUrl.getPath().endsWith("/")
                && (verificationUrl == null || secureUri(verificationUrl));
    }
    private static boolean positive(Duration value) { return !value.isNegative() && !value.isZero(); }
    private static boolean secureUri(URI value) {
        return value != null && "https".equalsIgnoreCase(value.getScheme()) && value.getHost() != null
                && value.getUserInfo() == null && value.getFragment() == null && value.getQuery() == null;
    }
}
