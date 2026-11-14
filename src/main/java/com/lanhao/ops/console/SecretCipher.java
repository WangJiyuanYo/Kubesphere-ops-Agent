package com.lanhao.ops.console;

import org.springframework.stereotype.Component;
import javax.crypto.Cipher;
import javax.crypto.spec.*;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import static java.nio.file.StandardOpenOption.*;

@Component
public class SecretCipher {
    private final ConsoleProperties config;
    private final SecureRandom random = new SecureRandom();
    private SecretKeySpec cached;
    public SecretCipher(ConsoleProperties config) { this.config = config; }
    public String encrypt(String value, String context) {
        if (value == null || value.isEmpty()) return null;
        try {
            byte[] nonce = new byte[12]; random.nextBytes(nonce);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(true), new GCMParameterSpec(128, nonce));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            byte[] ciphertext = cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(ByteBuffer.allocate(nonce.length + ciphertext.length)
                    .put(nonce).put(ciphertext).array());
        } catch (Exception e) { throw new ConsoleFailure("无法加密配置，请检查服务端密钥和数据目录权限"); }
    }
    public String decrypt(String value, String context) {
        if (value == null || value.isEmpty()) return "";
        try {
            if (!value.startsWith("v1:")) throw new IllegalArgumentException();
            byte[] bytes = Base64.getDecoder().decode(value.substring(3));
            if (bytes.length < 28) throw new IllegalArgumentException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(false), new GCMParameterSpec(128, bytes, 0, 12));
            cipher.updateAAD(context.getBytes(StandardCharsets.UTF_8));
            return new String(cipher.doFinal(bytes, 12, bytes.length - 12), StandardCharsets.UTF_8);
        } catch (Exception e) { throw new ConsoleFailure("无法解密配置，请恢复原服务端主密钥或重新保存凭据"); }
    }
    private synchronized SecretKeySpec key(boolean create) throws Exception {
        if (cached != null) return cached;
        byte[] bytes;
        if (config.encryptionKey() != null && !config.encryptionKey().isBlank()) {
            bytes = Base64.getDecoder().decode(config.encryptionKey());
        } else {
            Path path = config.keyFile().toAbsolutePath().normalize();
            if (create) Files.createDirectories(path.getParent());
            if (!create && !Files.exists(path)) throw new IllegalStateException();
            try (FileChannel file = FileChannel.open(path, create ? new StandardOpenOption[]{CREATE, READ, WRITE} : new StandardOpenOption[]{READ, WRITE});
                 var ignored = file.lock()) {
                if (file.size() == 0 && create) {
                    if (Files.getFileStore(path).supportsFileAttributeView("posix"))
                        Files.setPosixFilePermissions(path, PosixFilePermissions.fromString("rw-------"));
                    bytes = new byte[32]; random.nextBytes(bytes);
                    file.write(ByteBuffer.wrap(bytes)); file.force(true);
                }
                if (file.size() != 32) throw new IllegalStateException();
                bytes = new byte[32]; file.position(0);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) if (file.read(buffer) < 0) throw new IllegalStateException();
            }
        }
        if (bytes.length != 32) throw new IllegalArgumentException();
        return cached = new SecretKeySpec(bytes, "AES");
    }
}
