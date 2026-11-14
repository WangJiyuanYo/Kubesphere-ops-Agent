package com.lanhao.ops.evidence;

import org.springframework.stereotype.Component;
import java.util.regex.Pattern;

@Component
public class Redactor {
    private static final Pattern CREDENTIAL = Pattern.compile(
            "(?i)((?:password|passwd|token|secret|api[-_]?key|authorization)\\s*[=:]\\s*)[^\\s,;]+" );
    private static final Pattern BEARER = Pattern.compile("(?i)Bearer\\s+[A-Za-z0-9._~+/=-]+");
    private static final Pattern USERINFO = Pattern.compile("(://)[^/@\\s]+:[^/@\\s]+@");
    private static final Pattern JSON_SECRET = Pattern.compile(
            "(?i)([\"'](?:password|passwd|token|secret|api[-_]?key|authorization)[\"']\\s*:\\s*[\"'])[^\"']*([\"'])");
    private static final Pattern PRIVATE_KEY = Pattern.compile(
            "(?s)-----BEGIN [^-]*PRIVATE KEY-----.*?-----END [^-]*PRIVATE KEY-----");
    public String clean(String text) {
        if (text == null) return "";
        String safe = PRIVATE_KEY.matcher(text).replaceAll("[私钥已脱敏]");
        safe = JSON_SECRET.matcher(safe).replaceAll("$1[已脱敏]$2");
        safe = BEARER.matcher(safe).replaceAll("Bearer [已脱敏]");
        safe = CREDENTIAL.matcher(safe).replaceAll("$1[已脱敏]");
        safe = USERINFO.matcher(safe).replaceAll("$1[已脱敏]@");
        return safe.substring(0, Math.min(safe.length(), 4000));
    }
}
