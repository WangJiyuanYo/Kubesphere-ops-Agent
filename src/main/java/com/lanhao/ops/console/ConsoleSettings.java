package com.lanhao.ops.console;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.validation.constraints.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;
import java.net.URI;
import java.util.*;

@Service
public class ConsoleSettings {
    public enum Provider { DASHSCOPE, OPENAI_COMPATIBLE }
    public record LlmInput(@Min(0) long revision, boolean enabled, @NotNull Provider provider,
            @NotBlank @Size(max=500) String baseUrl, @NotBlank @Size(max=100) String model,
            @Size(max=4096) String apiKey, boolean clearApiKey, @DecimalMin("0") @DecimalMax("2") double temperature,
            @Min(128) @Max(4096) int maxTokens) {}
    public record Llm(long revision, boolean enabled, Provider provider, String baseUrl, String model,
            String encryptedKey, double temperature, int maxTokens) {}
    public record LlmView(long revision, boolean enabled, Provider provider, String baseUrl, String model,
            boolean hasApiKey, double temperature, int maxTokens, boolean saved) {}
    public record ModelCatalogInput(@Min(0) long revision,@NotNull Provider provider,
            @NotBlank @Size(max=500) String baseUrl,@Size(max=4096) String apiKey,boolean clearApiKey) {}
    public record ConnectionInput(@Min(0) long revision, @NotBlank @Size(max=80) String name,
            @NotBlank @Size(max=500) String apiUrl, @Size(max=500) String consoleUrl,
            @NotBlank @Size(max=63) @Pattern(regexp="[a-z0-9]([-a-z0-9]*[a-z0-9])?") String namespace,
            @Size(max=8192) String token, boolean clearToken,
            @Size(max=32768) String caCertificate, boolean clearCaCertificate) {
        public ConnectionInput(long revision,String name,String apiUrl,String consoleUrl,String namespace,String token,boolean clearToken) {
            this(revision,name,apiUrl,consoleUrl,namespace,token,clearToken,null,false);
        }
    }
    public record Connection(UUID id, long revision, String name, String apiUrl, String consoleUrl,
            String namespace, String encryptedToken, String encryptedCaCertificate) {
        public Connection(UUID id,long revision,String name,String apiUrl,String consoleUrl,String namespace,String encryptedToken) {
            this(id,revision,name,apiUrl,consoleUrl,namespace,encryptedToken,null);
        }
    }
    public record ConnectionView(UUID id, long revision, String name, String apiUrl, String consoleUrl,
            String namespace, boolean hasToken, boolean hasCaCertificate) {}
    private final JdbcTemplate jdbc;
    private final com.lanhao.ops.persistence.SqlDialect db;
    private final TransactionTemplate tx;
    private final ObjectMapper json;
    private final SecretCipher cipher;
    private final ConsoleProperties config;
    public ConsoleSettings(JdbcTemplate jdbc, TransactionTemplate tx, ObjectMapper json, SecretCipher cipher, ConsoleProperties config) {
        this.jdbc = jdbc; this.db = new com.lanhao.ops.persistence.SqlDialect(jdbc); this.tx=tx; this.json=json; this.cipher=cipher; this.config=config;
    }
    public Optional<Llm> llm() {
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_console_llm WHERE id=1"), (rs,i)->decode(rs.getString(1),Llm.class)).stream().findFirst();
    }
    public LlmView llmView() {
        return llm().map(this::view).orElse(new LlmView(0,false,Provider.DASHSCOPE,"https://dashscope.aliyuncs.com","qwen-plus",false,0.1,1500,false));
    }
    public LlmView saveLlm(LlmInput input) {
        String url=validateUrl(input.baseUrl());
        return tx.execute(status->{
            var rows=jdbc.query(db.sql("SELECT payload@text@ FROM agent_console_llm WHERE id=1@lock@"),(rs,i)->decode(rs.getString(1),Llm.class));
            Llm old=rows.isEmpty()?null:rows.getFirst();
            revision(input.revision(),old==null?0:old.revision());
            String key=secret(input.apiKey(),input.clearApiKey(),old==null?null:old.encryptedKey(),"llm");
            if(old!=null&&(!old.baseUrl().equals(url)||old.provider()!=input.provider())
                    &&(input.apiKey()==null||input.apiKey().isBlank())&&!input.clearApiKey()&&key!=null)
                throw new IllegalArgumentException("更换模型服务地址或协议时请重新输入 API Key 或清除原密钥");
            if(input.enabled()&&key==null) throw new IllegalArgumentException("启用模型前请填写 API Key");
            Llm next=new Llm(input.revision()+1,input.enabled(),input.provider(),url,input.model().trim(),key,input.temperature(),input.maxTokens());
            if(old==null) jdbc.update(db.sql("INSERT INTO agent_console_llm(id,revision,payload) VALUES(1,?,@json@)"),next.revision(),encode(next));
            else jdbc.update(db.sql("UPDATE agent_console_llm SET revision=?,payload=@json@,updated_at=@now@ WHERE id=1"),next.revision(),encode(next));
            return view(next);
        });
    }
    public List<ConnectionView> connections() {
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_console_connection ORDER BY updated_at DESC,id"),(rs,i)->view(decode(rs.getString(1),Connection.class)));
    }
    public Connection connection(UUID id) {
        return jdbc.query(db.sql("SELECT payload@text@ FROM agent_console_connection WHERE id=?"),(rs,i)->decode(rs.getString(1),Connection.class),id)
                .stream().findFirst().orElseThrow(()->new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.NOT_FOUND,"连接不存在"));
    }
    public ConnectionView saveConnection(UUID id, ConnectionInput input) {
        String apiUrl=validateUrl(input.apiUrl());
        String consoleUrl=input.consoleUrl()==null||input.consoleUrl().isBlank()?"":validateUrl(input.consoleUrl());
        String ca=input.clearCaCertificate()?null:ConnectionTls.validate(input.caCertificate());
        return tx.execute(status->{
            var rows=jdbc.query(db.sql("SELECT payload@text@ FROM agent_console_connection WHERE id=?@lock@"),(rs,i)->decode(rs.getString(1),Connection.class),id);
            Connection old=rows.isEmpty()?null:rows.getFirst(); revision(input.revision(),old==null?0:old.revision());
            String token=secret(input.token(),input.clearToken(),old==null?null:old.encryptedToken(),"connection:"+id);
            // 更换网关不能隐式携带旧网关凭据，调用者须重新输入凭据或明确清除。
            if(old!=null&&!old.apiUrl().equals(apiUrl)&&(input.token()==null||input.token().isBlank())&&!input.clearToken()&&token!=null)
                throw new IllegalArgumentException("更换 API 地址时请重新输入 Token 或清除原 Token");
            String encryptedCa=secret(ca,input.clearCaCertificate(),old==null?null:old.encryptedCaCertificate(),"connection-ca:"+id);
            if(old!=null&&!old.apiUrl().equals(apiUrl)&&ca==null&&!input.clearCaCertificate()&&encryptedCa!=null)
                throw new IllegalArgumentException("更换 API 地址时请重新输入 CA 证书或清除原证书");
            if(encryptedCa!=null&&!apiUrl.startsWith("https://"))
                throw new IllegalArgumentException("自定义 CA 证书仅用于 HTTPS 地址");
            Connection next=new Connection(id,input.revision()+1,input.name().trim(),apiUrl,consoleUrl,input.namespace(),token,encryptedCa);
            if(old==null) jdbc.update(db.sql("INSERT INTO agent_console_connection(id,revision,payload) VALUES(?,?,@json@)"),id,next.revision(),encode(next));
            else jdbc.update(db.sql("UPDATE agent_console_connection SET revision=?,payload=@json@,updated_at=@now@ WHERE id=?"),next.revision(),encode(next),id);
            return view(next);
        });
    }
    public void deleteConnection(UUID id,long revision) {
        if(jdbc.update("DELETE FROM agent_console_connection WHERE id=? AND revision=?",id,revision)!=1)
            throw new IllegalStateException("连接已变化，请刷新后重试");
    }
    public String llmKey(Llm llm) { return cipher.decrypt(llm.encryptedKey(),"llm"); }
    public String modelCatalogKey(ModelCatalogInput input,String url) {
        if(input.clearApiKey())throw new ConsoleFailure("已勾选清除 API Key，请先取消清除并填写密钥后获取模型");
        if(input.apiKey()!=null&&!input.apiKey().isBlank())return input.apiKey().trim();
        Llm saved=llm().orElseThrow(()->new ConsoleFailure("请填写 API Key 后获取模型，无需先保存配置"));
        revision(input.revision(),saved.revision());
        if(saved.provider()!=input.provider()||!saved.baseUrl().equals(url))
            throw new ConsoleFailure("地址或协议已改变，请重新填写 API Key 后获取模型");
        String key=llmKey(saved);
        if(key.isBlank())throw new ConsoleFailure("请填写 API Key 后获取模型，无需先保存配置");
        return key;
    }
    public String connectionToken(Connection connection) { return cipher.decrypt(connection.encryptedToken(),"connection:"+connection.id()); }
    public String connectionCaCertificate(Connection connection) { return cipher.decrypt(connection.encryptedCaCertificate(),"connection-ca:"+connection.id()); }
    private String secret(String value,boolean clear,String previous,String context) {
        if(clear) return null;
        return value==null||value.isBlank()?previous:cipher.encrypt(value.trim(),context);
    }
    public String validateUrl(String value) {
        try {
            URI uri=URI.create(value.trim());
            if(uri.getHost()==null||uri.getUserInfo()!=null||uri.getQuery()!=null||uri.getFragment()!=null
                    ||!("https".equalsIgnoreCase(uri.getScheme())||config.allowHttp()&&"http".equalsIgnoreCase(uri.getScheme()))) throw new IllegalArgumentException();
            return uri.toString().replaceAll("/+$","");
        } catch(RuntimeException e) { throw new IllegalArgumentException("地址需使用 HTTPS，且不能携带账号、查询参数或片段"); }
    }
    private static void revision(long expected,long actual) {
        if(expected!=actual) throw new IllegalStateException("配置已被更新，请刷新后重试");
    }
    private LlmView view(Llm value) { return new LlmView(value.revision(),value.enabled(),value.provider(),value.baseUrl(),value.model(),value.encryptedKey()!=null,value.temperature(),value.maxTokens(),true); }
    private ConnectionView view(Connection value) { return new ConnectionView(value.id(),value.revision(),value.name(),value.apiUrl(),value.consoleUrl(),value.namespace(),value.encryptedToken()!=null,value.encryptedCaCertificate()!=null); }
    private String encode(Object value) {
        try{return json.writeValueAsString(value);}catch(JsonProcessingException e){throw new ConsoleFailure("配置保存失败");}
    }
    private <T>T decode(String value,Class<T> type) {
        try{return json.readValue(value,type);}catch(JsonProcessingException e){throw new ConsoleFailure("配置读取失败");}
    }
}
