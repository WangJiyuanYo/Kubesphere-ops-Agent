package com.lanhao.ops.console;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.boot.context.properties.bind.ConstructorBinding;
import org.springframework.validation.annotation.Validated;
import jakarta.validation.constraints.*;
import java.nio.file.Path;
import java.time.Duration;
import org.hibernate.validator.constraints.time.DurationMin;
import org.hibernate.validator.constraints.time.DurationMax;

@Validated
@ConfigurationProperties("agent.console")
public record ConsoleProperties(String encryptionKey, @DefaultValue("./data/console-master.key") Path keyFile,
        @DefaultValue("false") boolean allowHttp, @DefaultValue("4") @Min(1) @Max(16) int chatSlots,
        @DefaultValue("45s") @NotNull @DurationMin(millis=100) @DurationMax(seconds=45) Duration modelReadTimeout) {
    @ConstructorBinding public ConsoleProperties {}
    public ConsoleProperties(String encryptionKey,Path keyFile,boolean allowHttp,int chatSlots) {
        this(encryptionKey,keyFile,allowHttp,chatSlots,Duration.ofSeconds(45));
    }
}
