package com.larkconnect.agent.audit;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRedactorTest {
    @Test
    void recursivelyRedactsSensitiveKeysAndBearerValues() {
        TraceRedactor redactor = new TraceRedactor();

        Object redacted = redactor.redact(Map.of(
                "token", "plain-token",
                "nested", List.of(
                        Map.of("api_key", "key-value"),
                        Map.of("message", "Authorization: Bearer abc.def.ghi")),
                "safe", "visible"));

        assertThat(redacted).isEqualTo(Map.of(
                "token", "***REDACTED***",
                "nested", List.of(
                        Map.of("api_key", "***REDACTED***"),
                        Map.of("message", "Authorization: Bearer ***REDACTED***")),
                "safe", "visible"));
    }

    @Test
    void redactsCiphertextAndSecretsInsideErrorText() {
        TraceRedactor redactor = new TraceRedactor();

        Object redacted = redactor.redact(Map.of(
                "mcpTokenCiphertext", "cipher-value",
                "error", "access_token=abc123 password: hello"));

        assertThat(redacted).isEqualTo(Map.of(
                "mcpTokenCiphertext", "***REDACTED***",
                "error", "access_token=***REDACTED*** password: ***REDACTED***"));
    }
}
