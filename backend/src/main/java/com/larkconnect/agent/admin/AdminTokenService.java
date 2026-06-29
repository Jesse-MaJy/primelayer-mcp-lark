package com.larkconnect.agent.admin;

import com.larkconnect.agent.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.Base64;

@Service
public class AdminTokenService {
    private final AppProperties properties;

    public AdminTokenService(AppProperties properties) {
        this.properties = properties;
    }

    public String issue(Long adminId, String username) {
        long expiresAt = Instant.now().getEpochSecond() + properties.admin().tokenTtlSeconds();
        String payload = adminId + ":" + username + ":" + expiresAt;
        return base64(payload) + "." + sign(payload);
    }

    public AdminPrincipal verify(String token) {
        if (token == null || !token.contains(".")) {
            return null;
        }
        String[] parts = token.split("\\.", 2);
        String payload = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(sign(payload).getBytes(StandardCharsets.UTF_8), parts[1].getBytes(StandardCharsets.UTF_8))) {
            return null;
        }
        String[] fields = payload.split(":", 3);
        if (fields.length != 3 || Long.parseLong(fields[2]) < Instant.now().getEpochSecond()) {
            return null;
        }
        return new AdminPrincipal(Long.parseLong(fields[0]), fields[1]);
    }

    private String sign(String payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret().getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(mac.doFinal(payload.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new IllegalStateException("Failed to sign admin token", e);
        }
    }

    private String base64(String payload) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
    }

    private String secret() {
        return "admin-token:" + properties.admin().bootstrapPassword();
    }
}
