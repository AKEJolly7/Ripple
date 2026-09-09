package com.ripple.dataprovider;

import com.ripple.domain.Ohlcv;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * mock 的 Gate.io candlesticks 响应验证解析与 999 天分段（无网络）。
 */
class GateIoProviderTest {

    // [tsSec, quoteVol, open, high, low, close, baseVol]；2024-01-01 = 1704067200
    private static final String PAGE = """
            [[1704067200,"100.0","42000.0","43000.0","41500.0","42500.0","12.5"],
             [1704153600,"110.0","42500.1","44000.0","42400.0","43800.2","13.6"],
             [1704240000,"120.0","43800.3","44500.0","43000.0","43500.4","14.7"],
             [1704326400,"130.0","43500.5","43700.0","42800.0","43000.6","15.8"]]""";

    @Test
    void parsesGateRowLayout() throws Exception {
        var fake = new FakeTransport(List.of(200), PAGE);
        List<Ohlcv> candles = new GateIoProvider(fake, 1000).fetchDaily("BTC-USD",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

        assertEquals(4, candles.size());
        assertEquals(1, fake.calls());
        Ohlcv first = candles.get(0);
        assertEquals(LocalDate.of(2024, 1, 1), first.date());
        // Gate 行布局：idx2=open, idx3=high, idx4=low, idx5=close, idx6=baseVolume
        assertEquals(42000.0, first.open(), 1e-9);
        assertEquals(43000.0, first.high(), 1e-9);
        assertEquals(41500.0, first.low(), 1e-9);
        assertEquals(42500.0, first.close(), 1e-9);
        assertEquals(13L, first.volume());
    }

    @Test
    void splitsRequestIntoSegmentsOfAtMost999Days() throws Exception {
        // 2021-09-01 ~ 2026-09-01 共 1826 天 → 2 段（999 + 827）
        var fake = new FakeTransport(List.of(200), "[]");
        new GateIoProvider(fake, 1000).fetchDaily("BTC-USD",
                LocalDate.of(2021, 9, 1), LocalDate.of(2026, 9, 1));

        assertEquals(2, fake.calls(), "1826 天应分 2 段请求（999+827）");
        List<String> uris = fake.uris().stream().map(String::valueOf).toList();
        // 每段覆盖天数 ≤ 999（to 为闭区间末秒：to-from+1 ≤ 999*86400）
        for (int i = 0; i < uris.size(); i++) {
            java.util.regex.Matcher m = java.util.regex.Pattern
                    .compile("from=(\\d+)&to=(\\d+)").matcher(uris.get(i));
            assertTrue(m.find(), uris.get(i));
            long span = Long.parseLong(m.group(2)) - Long.parseLong(m.group(1)) + 1;
            assertTrue(span <= 999 * 86400,
                    "第 " + (i + 1) + " 段跨度 " + (span / 86400) + " 天超过 999 天限制");
        }
        // 段与段无缝衔接：第 2 段 from = 第 1 段 to + 1 秒
        java.util.regex.Matcher m1 = java.util.regex.Pattern
                .compile("from=\\d+&to=(\\d+)").matcher(uris.get(0));
        java.util.regex.Matcher m2 = java.util.regex.Pattern
                .compile("from=(\\d+)").matcher(uris.get(1));
        assertTrue(m1.find() && m2.find());
        assertEquals(Long.parseLong(m1.group(1)) + 1, Long.parseLong(m2.group(1)),
                "段间应无缝衔接");
    }
}
