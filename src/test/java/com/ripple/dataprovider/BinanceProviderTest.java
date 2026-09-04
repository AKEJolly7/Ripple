package com.ripple.dataprovider;

import com.ripple.domain.Ohlcv;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 mock 的 Binance klines 响应验证翻页拉取与解析，不访问真实网络。
 */
class BinanceProviderTest {

    // 页大小 3：第一页 3 根（触发翻页），第二页 1 根（尾页）
    // 2024-01-01/02/03/04 的 openTime(ms)：1704067200000 起，每天 +86400000
    private static final String PAGE1 = """
            [[1704067200000,"42280.0","43200.0","42000.0","42800.0","1234.5",1704153599999],
             [1704153600000,"42800.1","44100.0","42700.0","44000.2","2345.6",1704239999999],
             [1704240000000,"44000.3","44500.0","43500.0","43600.4","3456.7",1704326399999]]""";
    private static final String PAGE2 = """
            [[1704326400000,"43600.5","43700.0","43000.0","43100.6","4567.8",1704412799999]]""";

    @Test
    void paginatesUntilShortPageAndParsesCandles() throws Exception {
        var fake = new FakeTransport(List.of(200, 200), List.of(PAGE1, PAGE2));
        var provider = new BinanceProvider(fake, 3);

        List<Ohlcv> candles = provider.fetchDaily("BTC-USD",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

        assertEquals(4, candles.size());
        assertEquals(2, fake.calls()); // 3 根满页 + 1 根尾页 = 2 次请求

        Ohlcv first = candles.getFirst();
        assertEquals(LocalDate.of(2024, 1, 1), first.date());
        assertEquals(42280.0, first.open(), 1e-9);
        assertEquals(43200.0, first.high(), 1e-9);
        assertEquals(42000.0, first.low(), 1e-9);
        assertEquals(42800.0, first.close(), 1e-9);
        assertEquals(1235L, first.volume());
        assertEquals(LocalDate.of(2024, 1, 4), candles.getLast().date());

        // 翻页游标：第二页 startTime = 第一页最后一根 openTime + 1
        String secondUri = fake.uris().get(1).toString();
        assertTrue(secondUri.contains("startTime=1704240000001"), secondUri);
        assertTrue(secondUri.contains("symbol=BTCUSDT"), secondUri);
        assertTrue(secondUri.contains("interval=1d"), secondUri);
    }

    @Test
    void emptyFirstPageYieldsNoCandles() throws Exception {
        var fake = new FakeTransport(List.of(200), "[]");
        var provider = new BinanceProvider(fake, 3);

        List<Ohlcv> candles = provider.fetchDaily("BTC-USD",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

        assertEquals(0, candles.size());
        assertEquals(1, fake.calls());
    }
}
