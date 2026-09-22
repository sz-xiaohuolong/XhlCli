package com.xhlcli.web;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class NetworkPolicyTest {

    @Test
    void testValidPublicUrlsPass() {
        NetworkPolicy policy = new NetworkPolicy();
        // example.com is a well-known public IANA domain
        assertNull(policy.checkUrl("https://example.com"));
        assertNull(policy.checkUrl("http://example.com/test?query=1#hash"));
    }

    @Test
    void testDisallowedSchemes() {
        NetworkPolicy policy = new NetworkPolicy();
        assertTrue(policy.checkUrl("file:///etc/passwd").contains("禁止的 scheme"));
        assertTrue(policy.checkUrl("ftp://example.com/file").contains("禁止的 scheme"));
        assertTrue(policy.checkUrl("gopher://example.com").contains("禁止的 scheme"));
        assertTrue(policy.checkUrl("example.com").contains("缺少 scheme"));
    }

    @Test
    void testNullOrBlankOrMalformed() {
        NetworkPolicy policy = new NetworkPolicy();
        assertEquals("URL 不能为空", policy.checkUrl(null));
        assertEquals("URL 不能为空", policy.checkUrl("   "));
        assertTrue(policy.checkUrl("http://   ").contains("URL 缺少 host") || policy.checkUrl("http://   ").contains("URL 格式非法"));
    }

    @Test
    void testLocalhostAndZeroLiteralBlocked() {
        NetworkPolicy policy = new NetworkPolicy();
        assertTrue(policy.checkUrl("http://localhost:8080").contains("禁止访问 localhost"));
        assertTrue(policy.checkUrl("http://api.localhost/v1").contains("禁止访问 localhost"));
        assertTrue(policy.checkUrl("http://0.0.0.0/test").contains("禁止访问 0.0.0.0"));
    }

    @Test
    void testPrivateAndLoopbackIpsBlocked() {
        NetworkPolicy policy = new NetworkPolicy();
        assertTrue(policy.checkUrl("http://127.0.0.1:8000/").contains("禁止访问环回地址"));
        assertTrue(policy.checkUrl("http://10.1.2.3/api").contains("禁止访问站内/私网地址"));
        assertTrue(policy.checkUrl("http://192.168.1.1/admin").contains("禁止访问站内/私网地址"));
        assertTrue(policy.checkUrl("http://172.16.0.1/status").contains("禁止访问站内/私网地址"));
    }

    @Test
    void testRateLimiterTokenBucket() {
        // Allow only 2 requests per 500ms
        NetworkPolicy policy = new NetworkPolicy(500L, 2);
        assertNull(policy.acquire());
        assertNull(policy.acquire());
        String blocked = policy.acquire();
        assertNotNull(blocked);
        assertTrue(blocked.contains("请求过于频繁"));
    }
}
