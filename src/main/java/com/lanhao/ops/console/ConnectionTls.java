package com.lanhao.ops.console;

import javax.net.ssl.*;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;

/** 每个连接使用独立信任集合，保留 TLS 证书链及主机名校验。 */
public final class ConnectionTls {
    private ConnectionTls() {}
    public static String validate(String pem) {
        if(pem==null||pem.isBlank())return null;
        certificates(pem);
        return pem.trim();
    }
    public static SSLContext context(String pem) {
        try {
            KeyStore trust=KeyStore.getInstance(KeyStore.getDefaultType());trust.load(null,null);
            int index=0;
            for(X509Certificate certificate:certificates(pem))trust.setCertificateEntry("ca-"+index++,certificate);
            TrustManagerFactory managers=TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            managers.init(trust);
            SSLContext context=SSLContext.getInstance("TLS");context.init(null,managers.getTrustManagers(),null);
            return context;
        }catch(IllegalArgumentException e){throw e;}
        catch(Exception e){throw new ConsoleFailure("无法创建连接证书信任配置");}
    }
    private static List<X509Certificate> certificates(String pem) {
        try {
            if(pem==null||pem.length()>32768||!pem.matches("(?s)\\s*(-----BEGIN CERTIFICATE-----[A-Za-z0-9+/=\\s]+-----END CERTIFICATE-----\\s*)+"))throw new IllegalArgumentException();
            var parsed=CertificateFactory.getInstance("X.509").generateCertificates(new ByteArrayInputStream(pem.getBytes(StandardCharsets.US_ASCII)));
            if(parsed.isEmpty()||parsed.size()>10)throw new IllegalArgumentException();
            List<X509Certificate> result=new ArrayList<>();
            for(var certificate:parsed){
                X509Certificate ca=(X509Certificate)certificate;
                if(ca.getBasicConstraints()<0)throw new IllegalArgumentException();
                result.add(ca);
            }
            return result;
        }catch(Exception e){throw new IllegalArgumentException("CA 证书必须是有效的 PEM 格式 X.509 CA 证书链，不能包含私钥");}
    }
}
