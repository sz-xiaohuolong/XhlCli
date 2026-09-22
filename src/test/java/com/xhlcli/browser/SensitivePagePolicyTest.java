package com.xhlcli.browser;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class SensitivePagePolicyTest {

    @Test
    void testDefaultSensitivePatterns() {
        SensitivePagePolicy policy = new SensitivePagePolicy(null);

        assertTrue(policy.isSensitive("https://www.alipay.com/login"));
        assertTrue(policy.isSensitive("https://paypal.com/signin"));
        assertTrue(policy.isSensitive("https://stripe.com/checkout"));
        assertTrue(policy.isSensitive("https://github.com/settings/tokens"));
        assertTrue(policy.isSensitive("https://console.aws.amazon.com/ec2/home"));
        assertTrue(policy.isSensitive("https://portal.azure.com/#home"));
        assertTrue(policy.isSensitive("https://console.cloud.google.com/"));
        assertTrue(policy.isSensitive("https://mybank.bank.com/account"));

        // Regular websites should not be sensitive
        assertFalse(policy.isSensitive("https://github.com/sz-xiaohuolong/XhlCli"));
        assertFalse(policy.isSensitive("https://spring.io/docs"));
        assertFalse(policy.isSensitive("https://example.com"));
    }

    @Test
    void testCustomPatternsFromFile(@TempDir Path tempDir) throws IOException {
        Path userRules = tempDir.resolve("sensitive_patterns.txt");
        Files.writeString(userRules, """
                # Custom rules
                *://internal.corp.com/*
                *://*.secure-vault.io/*
                """);

        SensitivePagePolicy policy = new SensitivePagePolicy(userRules);
        assertTrue(policy.isSensitive("https://internal.corp.com/admin"));
        assertTrue(policy.isSensitive("https://app.secure-vault.io/keys"));
        assertFalse(policy.isSensitive("https://open.corp.com"));
    }
}
