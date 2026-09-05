package com.miqroera.miqrokey.gateway.retention;

import com.miqroera.miqrokey.domain.crypto.EncryptedSecret;
import com.miqroera.miqrokey.domain.crypto.KeyEncryptionProvider;
import com.miqroera.miqrokey.domain.model.RetentionConfig;
import com.miqroera.miqrokey.domain.model.RetentionEnvelope;
import com.miqroera.miqrokey.gateway.GatewayAuthTestConfig;
import com.miqroera.miqrokey.testing.AnthropicMockProvider;
import com.miqroera.miqrokey.testing.GatewayTestKeys;
import com.miqroera.miqrokey.testing.InMemoryRouteSnapshotProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ADR-0014 R2 end-to-end over the real proxy path (chat completions shape):
 * with the tenant retention switch on, one authenticated request carrying user
 * text yields exactly one {@link RetentionEnvelope} on the publisher and the
 * upstream still sees the original bytes; with the switch off, nothing is
 * captured. The test crypto provider is an invertible stand-in — the real
 * AES-GCM path is covered by the domain crypto suite; the pipeline concerns
 * here are gating, extraction, envelope shape and byte-transparency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.autoconfigure.exclude=org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration,"
                + "org.springframework.boot.autoconfigure.jdbc.DataSourceTransactionManagerAutoConfiguration",
        "miqrokey.gateway.persistence.enabled=false", "miqrokey.crypto.enabled=false",
        "miqrokey.retention.flush-interval-ms=50", "spring.main.web-application-type=reactive"})
@Import({GatewayAuthTestConfig.class, RetentionIntegrationTest.TestCryptoAndPublisher.class})
@DisplayName("Retention side-channel end-to-end (ADR-0014 R2)")
class RetentionIntegrationTest {

    /** Invertible stand-in cipher: base64 plaintext in the ciphertext slot. */
    static final class FakeCrypto implements KeyEncryptionProvider {
        @Override
        public EncryptedSecret encrypt(byte[] plaintext, UUID tenantId, UUID credentialId) {
            return new EncryptedSecret(Base64.getEncoder().encode(plaintext), new byte[]{1}, "v1");
        }

        @Override
        public byte[] decrypt(EncryptedSecret secret, UUID tenantId, UUID credentialId) {
            return Base64.getDecoder().decode(secret.ciphertext());
        }

        @Override
        public String activeKeyVersion() {
            return "v1";
        }

        @Override
        public EncryptedSecret reEncrypt(EncryptedSecret secret, UUID tenantId, UUID credentialId) {
            return secret;
        }
    }

    static final class FakeRetentionPublisher implements RetentionPublisher {
        final List<RetentionEnvelope> published = new CopyOnWriteArrayList<>();

        @Override
        public void publish(RetentionEnvelope envelope) {
            published.add(envelope);
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class TestCryptoAndPublisher {
        @Bean
        @Primary
        RetentionPublisher fakeRetentionPublisher() {
            return new FakeRetentionPublisher();
        }

        @Bean
        @Primary
        KeyEncryptionProvider fakeKeyEncryptionProvider() {
            return new FakeCrypto();
        }
    }

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private InMemoryRouteSnapshotProvider snapshotProvider;

    @Autowired
    private FakeRetentionPublisher publisher;

    private static final AnthropicMockProvider mockProvider = new AnthropicMockProvider();

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("miqrokey.gateway.upstream.url", mockProvider::getBaseUrl);
    }

    @AfterAll
    static void stopMockProvider() {
        mockProvider.close();
    }

    @BeforeEach
    void resetState() {
        mockProvider.reset();
        publisher.published.clear();
        installRetention(false);
    }

    @AfterEach
    void cleanState() {
        publisher.published.clear();
        installRetention(false);
    }

    private void installRetention(boolean enabled) {
        RetentionConfig config = new RetentionConfig(enabled, RetentionConfig.USER_TEXT_ONLY, "v1", 1);
        snapshotProvider.install(GatewayTestKeys.snapshotWithRetention(mockProvider.getBaseUrl(), Map.of(),
                Map.of(GatewayTestKeys.TENANT_ID, config), GatewayTestKeys.DEFAULT_KEY));
    }

    private static final String CHAT_BODY = """
            {"model":"demo-model","messages":[
              {"role":"system","content":"secret system prompt"},
              {"role":"user","content":"remember this phrase"},
              {"role":"assistant","content":"ok"},
              {"role":"user","content":[{"type":"text","text":"and this one"}]}
            ]}""";

    private void postChat() {
        mockProvider.configure(
                AnthropicMockProvider.ResponseConfig.builder().statusCode(200).contentType("application/json")
                        .body("{\"id\":\"chatcmpl-test\",\"object\":\"chat.completion\","
                                + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"ok\"},"
                                + "\"finish_reason\":\"stop\"}]}")
                        .build());
        webTestClient.post().uri("/v1/chat/completions")
                .headers(h -> h.set(HttpHeaders.AUTHORIZATION, "Bearer " + GatewayTestKeys.DEFAULT_KEY.presented()))
                .bodyValue(CHAT_BODY).exchange().expectStatus().isOk();
    }

    private void awaitPublished(int expected) {
        long deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos();
        while (System.nanoTime() < deadline) {
            if (publisher.published.size() >= expected) {
                return;
            }
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("timed out waiting for " + expected + " envelopes, saw " + publisher.published.size());
    }

    @Test
    @DisplayName("enabled retention captures one envelope with exactly the user text")
    void capturesWhenEnabled() {
        installRetention(true);
        postChat();
        awaitPublished(1);

        RetentionEnvelope envelope = publisher.published.get(0);
        assertThat(envelope.tenantId()).isEqualTo(GatewayTestKeys.TENANT_ID);
        assertThat(envelope.userId()).isNotNull();
        assertThat(envelope.virtualKeyId()).isNotNull();
        assertThat(envelope.wireProtocol()).isEqualTo("OPENAI_CHAT");
        assertThat(envelope.textCharCount()).isGreaterThan(0);
        assertThat(envelope.keyVersion()).isEqualTo("v1");

        byte[] plain = new FakeCrypto().decrypt(
                new EncryptedSecret(envelope.ciphertext(), envelope.nonce(), envelope.keyVersion()),
                envelope.tenantId(), UUID.randomUUID());
        String text = new String(plain, StandardCharsets.UTF_8);
        assertThat(text).isEqualTo("remember this phrase\n---\nand this one");
        assertThat(text).doesNotContain("secret system prompt");
        // The forwarded request is untouched.
        assertThat(mockProvider.getCapturedRequests()).hasSize(1);
    }

    @Test
    @DisplayName("disabled retention never captures")
    void disabledCapturesNothing() {
        postChat();
        try {
            Thread.sleep(600);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
        assertThat(publisher.published).isEmpty();
    }
}
