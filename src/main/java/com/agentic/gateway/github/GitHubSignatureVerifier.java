package com.agentic.gateway.github;

import com.agentic.gateway.config.GitHubWebhookProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * GitHub Webhook HMAC SHA-256 簽章驗證器。
 *
 * <p>驗證邏輯獨立於 Controller，讓安全責任集中且可測試。
 * 比對簽章時使用常數時間比較，降低 timing attack 風險。</p>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class GitHubSignatureVerifier {

    private static final String SIGNATURE_PREFIX = "sha256=";
    private static final String HMAC_SHA_256 = "HmacSHA256";

    private final GitHubWebhookProperties gitHubWebhookProperties;

    public boolean isValid(String signatureHeader, String rawPayload) {
        String secret = gitHubWebhookProperties.secret();
        if (secret == null || secret.isBlank()) {
            log.error("GITHUB_WEBHOOK_SECRET is not configured.");
            return false;
        }

        if (signatureHeader == null || !signatureHeader.startsWith(SIGNATURE_PREFIX)) {
            return false;
        }

        String expectedSignature = SIGNATURE_PREFIX + hmacSha256Hex(secret, rawPayload);
        return constantTimeEquals(expectedSignature, signatureHeader);
    }

    private String hmacSha256Hex(String secret, String rawPayload) {
        try {
            Mac mac = Mac.getInstance(HMAC_SHA_256);
            SecretKeySpec secretKey = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_SHA_256);
            mac.init(secretKey);
            byte[] digest = mac.doFinal(rawPayload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (Exception ex) {
            throw new IllegalStateException("GitHub Webhook HMAC 計算失敗", ex);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        try {
            byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
            byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
            return MessageDigest.isEqual(expectedBytes, actualBytes);
        } catch (Exception ex) {
            if (ex instanceof NoSuchAlgorithmException) {
                log.error("Unexpected digest algorithm issue.", ex);
            }
            return false;
        }
    }
}
