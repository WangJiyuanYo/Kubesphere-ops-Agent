package com.lanhao.ops.notification;

import com.lanhao.ops.OpsProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.util.*;

@Component
public class NotificationDispatcher {
    private final JdbcTemplate jdbc;
    private final com.lanhao.ops.persistence.SqlDialect db;
    private final TransactionTemplate tx;
    private final OpsProperties ops;
    private final RestClient client;
    public NotificationDispatcher(JdbcTemplate jdbc, TransactionTemplate tx, OpsProperties ops) {
        this.jdbc = jdbc; this.db = new com.lanhao.ops.persistence.SqlDialect(jdbc); this.tx = tx; this.ops = ops;
        var factory = new JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3))
                .followRedirects(HttpClient.Redirect.NEVER).build());
        factory.setReadTimeout(Duration.ofSeconds(5));
        client = RestClient.builder().requestFactory(factory).build();
    }
    private record Delivery(UUID id, String payload, int attempts, UUID owner) {}
    @Scheduled(fixedDelayString = "${agent.notification-poll-interval:2000}")
    public void dispatch() {
        if (!ops.notification().enabled()) return;
        UUID owner = UUID.randomUUID();
        List<Delivery> claimed = tx.execute(status -> {
            jdbc.update(db.sql("UPDATE agent_notification SET state='DEAD',lease_owner=NULL,lease_until=NULL "
                            + "WHERE state='PENDING' AND attempts>=? AND (lease_until IS NULL OR lease_until<@now@)"),
                    ops.notification().maxAttempts());
            if(db.sqlite()) {
                var ids=jdbc.query(db.sql("SELECT id FROM agent_notification WHERE state='PENDING' AND due_at<=@now@ "
                        +"AND attempts<? AND (lease_until IS NULL OR lease_until<@now@) ORDER BY due_at LIMIT 1"),
                        (rs,i)->com.lanhao.ops.persistence.SqlDialect.uuid(rs,1),ops.notification().maxAttempts());
                for(UUID id:ids) jdbc.update(db.sql("UPDATE agent_notification SET lease_owner=?,lease_until=@notificationUntil@,attempts=attempts+1 WHERE id=?"),owner,id);
                return ids.stream().map(id->jdbc.queryForObject("SELECT id,payload,attempts FROM agent_notification WHERE id=?",
                        (rs,i)->new Delivery(com.lanhao.ops.persistence.SqlDialect.uuid(rs,1),rs.getString(2),rs.getInt(3),owner),id)).toList();
            }
            return jdbc.query(db.sql("""
                WITH c AS (SELECT id FROM agent_notification WHERE state='PENDING' AND due_at<=@now@
                    AND attempts<? AND (lease_until IS NULL OR lease_until<@now@) ORDER BY due_at@skipLocked@ LIMIT 1)
                UPDATE agent_notification n SET lease_owner=?,lease_until=@notificationUntil@,attempts=attempts+1
                FROM c WHERE n.id=c.id RETURNING n.id,n.payload@text@,n.attempts
                """), (rs, i) -> new Delivery(com.lanhao.ops.persistence.SqlDialect.uuid(rs,1), rs.getString(2), rs.getInt(3), owner),
                    ops.notification().maxAttempts(), owner);
        });
        for (Delivery delivery : claimed) {
            boolean success;
            try {
                String timestamp = Long.toString(Instant.now().getEpochSecond());
                int status = client.post().uri(ops.notification().url()).contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                        .header("X-Agent-Event-Id", delivery.id().toString()).header("X-Agent-Timestamp", timestamp)
                        .header("X-Agent-Signature", sign(ops.notification().secret(), timestamp, delivery.payload()))
                        .body(delivery.payload()).exchange((request, response) -> response.getStatusCode().value());
                success = status >= 200 && status < 300;
            } catch (RuntimeException failure) { success = false; }
            String state = success ? "SENT" : delivery.attempts() >= ops.notification().maxAttempts() ? "DEAD" : "PENDING";
            int delay = Math.min(3600, 5 * (1 << Math.min(delivery.attempts(), 9)));
            jdbc.update(db.sql("UPDATE agent_notification SET state=?,due_at=@afterSeconds@,lease_owner=NULL,lease_until=NULL "
                            + "WHERE id=? AND lease_owner=? AND lease_until>@now@"), state, delay, delivery.id(), delivery.owner());
        }
    }
    public static String sign(String secret, String timestamp, String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + payload).getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException failure) { throw new IllegalStateException("通知签名不可用"); }
    }
}
