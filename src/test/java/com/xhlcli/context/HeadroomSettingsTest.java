package com.xhlcli.context;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.Duration;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class HeadroomSettingsTest {
    @Test void 默认关闭且不解析未启用的服务配置() {
        var settings = HeadroomSettings.fromEnvironment(Map.of("XHLCLI_HEADROOM_URL", "损坏地址", "XHLCLI_HEADROOM_TIMEOUT_MS", "错误"));
        assertFalse(settings.enabled());
    }

    @Test void 开启后读取地址与整次调用超时() {
        var settings = HeadroomSettings.fromEnvironment(Map.of("XHLCLI_HEADROOM_ENABLED", " TRUE ",
                "XHLCLI_HEADROOM_URL", "http://127.0.0.1:9000/", "XHLCLI_HEADROOM_TIMEOUT_MS", "250"));
        assertTrue(settings.enabled());
        assertEquals("http://127.0.0.1:9000/v1/compress", settings.endpoint().toString());
        assertEquals(Duration.ofMillis(250), settings.timeout());
    }

    @ParameterizedTest @ValueSource(strings = {"http://example.com", "http://192.168.1.1", "file:///tmp/a",
            "http://user:secret@127.0.0.1:8787", "http://127.0.0.1:8787/path", "http://127.0.0.1:8787/?key=secret",
            "http://127.0.0.1:8787/#secret", "http://127.0.0.1:0", "http://127.0.0.1:99999"})
    void 不允许正文被发送到非约定地址(String url) {
        var error = assertThrows(IllegalArgumentException.class, () -> HeadroomSettings.fromEnvironment(
                Map.of("XHLCLI_HEADROOM_ENABLED", "true", "XHLCLI_HEADROOM_URL", url)));
        assertFalse(error.getMessage().contains("secret"));
    }

    @ParameterizedTest @ValueSource(strings = {"0", "99", "30001", "abc"})
    void 拒绝非法超时(String timeout) {
        assertThrows(IllegalArgumentException.class, () -> HeadroomSettings.fromEnvironment(Map.of(
                "XHLCLI_HEADROOM_ENABLED", "true", "XHLCLI_HEADROOM_TIMEOUT_MS", timeout)));
    }

    @Test void 非法开关不能悄悄视为关闭() {
        assertThrows(IllegalArgumentException.class, () -> HeadroomSettings.fromEnvironment(Map.of("XHLCLI_HEADROOM_ENABLED", "yes")));
    }
}
