package com.lanhao.ops.console;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import static org.assertj.core.api.Assertions.*;

class SecretCipherTest {
    @TempDir Path directory;
    @Test void encryptsWithRandomNonceAndBindsCredentialToItsOwner() {
        var config=new ConsoleProperties(null,directory.resolve("key"),false,4);
        var cipher=new SecretCipher(config);
        String encrypted=cipher.encrypt("test-secret","llm");
        assertThat(encrypted).doesNotContain("test-secret").isNotEqualTo(cipher.encrypt("test-secret","llm"));
        assertThat(new SecretCipher(config).decrypt(encrypted,"llm")).isEqualTo("test-secret");
        assertThatThrownBy(()->cipher.decrypt(encrypted,"connection:other" )).isInstanceOf(ConsoleFailure.class);
    }
    @Test void missingMasterKeyCannotSilentlyCreateAnUnreadableReplacement() throws Exception {
        Path key=directory.resolve("key");
        var config=new ConsoleProperties(null,key,false,4);
        String encrypted=new SecretCipher(config).encrypt("test-secret","llm");
        Files.delete(key);
        assertThatThrownBy(()->new SecretCipher(config).decrypt(encrypted,"llm")).isInstanceOf(ConsoleFailure.class);
        assertThat(key).doesNotExist();
    }
}
