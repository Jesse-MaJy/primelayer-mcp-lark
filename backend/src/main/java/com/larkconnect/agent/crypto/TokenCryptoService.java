package com.larkconnect.agent.crypto;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.Base64;

@Service
public class TokenCryptoService {
    private static final String CONFIG_KEY = "mcp.token.master_key";
    private final JdbcTemplate jdbcTemplate;
    private final SecureRandom secureRandom = new SecureRandom();

    public TokenCryptoService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public String encrypt(String plaintext) {
        try {
            byte[] iv = new byte[12];
            secureRandom.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, loadKey(), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ciphertext);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to encrypt MCP token", e);
        }
    }

    public String decrypt(String encrypted) {
        try {
            String[] parts = encrypted.split(":", 2);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, loadKey(), new GCMParameterSpec(128, Base64.getDecoder().decode(parts[0])));
            return new String(cipher.doFinal(Base64.getDecoder().decode(parts[1])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to decrypt MCP token", e);
        }
    }

    private SecretKey loadKey() {
        String value = jdbcTemplate.query("select config_value from system_config where config_key = ?",
                rs -> rs.next() ? rs.getString(1) : null,
                CONFIG_KEY);
        if (value == null) {
            value = generateAndStoreKey();
        }
        return new SecretKeySpec(Base64.getDecoder().decode(value), "AES");
    }

    private String generateAndStoreKey() {
        try {
            KeyGenerator keyGenerator = KeyGenerator.getInstance("AES");
            keyGenerator.init(256);
            String encoded = Base64.getEncoder().encodeToString(keyGenerator.generateKey().getEncoded());
            jdbcTemplate.update("""
                    insert into system_config(config_key, config_value, description, is_sensitive)
                    values (?, ?, ?, 1)
                    """, CONFIG_KEY, encoded, "MCP token AES-GCM master key. MVP stores this in DB; migrate to env/KMS before production.");
            return encoded;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to create token master key", e);
        }
    }
}
