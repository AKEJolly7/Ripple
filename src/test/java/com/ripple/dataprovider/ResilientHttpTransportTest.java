package com.ripple.dataprovider;

import com.ripple.dataprovider.api.HttpTransport;

import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 验证指数退避重试语义：429/5xx 重试、4xx 立即失败、耗尽后按类型抛出。
 */
class ResilientHttpTransportTest {

    private static final URI ANY = URI.create("https://example.invalid/api");

    @Test
    void retries429And5xxThenSucceeds() throws Exception {
        var fake = new FakeTransport(List.of(429, 500, 200), "ok");
        var transport = new ResilientHttpTransport(fake, 3, 1);

        HttpTransport.Response resp = transport.get(ANY);

        assertEquals(200, resp.status());
        assertEquals("ok", resp.body());
        assertEquals(3, fake.calls());
    }

    @Test
    void doesNotRetryClientErrors() {
        var fake = new FakeTransport(List.of(404), "not found");
        var transport = new ResilientHttpTransport(fake, 3, 1);

        var e = assertThrows(HttpFetchException.class, () -> transport.get(ANY));

        assertEquals(HttpFetchException.Type.CLIENT, e.type());
        assertEquals(1, fake.calls()); // 4xx（非 429）不重试
    }

    @Test
    void throwsRateLimitedAfterExhaustingRetries() {
        var fake = new FakeTransport(List.of(429), "slow down");
        var transport = new ResilientHttpTransport(fake, 2, 1);

        var e = assertThrows(HttpFetchException.class, () -> transport.get(ANY));

        assertEquals(HttpFetchException.Type.RATE_LIMITED, e.type());
        assertEquals(3, fake.calls()); // 1 次原始 + 2 次重试
    }

    @Test
    void backoffIsExponentialWithJitterWithinBounds() {
        var transport = new ResilientHttpTransport(new FakeTransport(List.of(200), "ok"), 5, 1_000);

        // 第 n 次重试基准 = 1000·2^(n-1)，含 ±25% 抖动，上限 8s
        for (int attempt = 1; attempt <= 5; attempt++) {
            long base = Math.min(1_000L << (attempt - 1), 8_000);
            for (int i = 0; i < 200; i++) {
                long b = transport.backoffMs(attempt);
                assertTrue(b >= base * 3 / 4 && b <= base * 5 / 4,
                        "attempt=" + attempt + " backoff=" + b + " 越界");
            }
        }
    }
}
