package com.ripple.dataprovider;

import com.ripple.domain.Ohlcv;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 mock 的 Yahoo 响应验证解析正确性（拆股复权、null 跳过、错误分类），不访问真实网络。
 */
class YahooFinanceProviderTest {

    // 4 个交易日（美股时区）：第 3 根 close 为 null（停牌占位，应跳过）；
    // adjclose 均为 close 的 0.5 倍（模拟 2:1 拆股后的复权因子）
    private static final String OK_BODY = """
            {"chart":{"result":[{
              "meta":{"symbol":"NVDA"},
              "timestamp":[1662110400,1662196800,1662283200,1662369600],
              "indicators":{
                "quote":[{
                  "open":[180.0,181.0,182.0,183.0],
                  "high":[185.0,186.0,null,188.0],
                  "low":[178.0,179.0,180.0,181.0],
                  "close":[182.0,183.0,null,185.0],
                  "volume":[1000,1100,0,1200]}],
                "adjclose":[{"adjclose":[91.0,91.5,null,92.5]}]}}],
              "error":null}}""";

    @Test
    void parsesCandlesWithAdjustFactorAndSkipsNullClose() throws Exception {
        var provider = new YahooFinanceProvider(new FakeTransport(List.of(200), OK_BODY));

        List<Ohlcv> candles = provider.fetchDaily("NVDA",
                LocalDate.of(2022, 9, 1), LocalDate.of(2022, 9, 10));

        assertEquals(3, candles.size()); // 第 3 根 close=null 被跳过
        Ohlcv first = candles.get(0);
        assertEquals(LocalDate.of(2022, 9, 2), first.date());
        // 复权因子 0.5：open 180→90，close 182→91
        assertEquals(90.0, first.open(), 1e-9);
        assertEquals(91.0, first.close(), 1e-9);
        assertEquals(1000L, first.volume());
        // 第 2 根：high 186 → 186*0.5=93.0（null 的 high 在被跳过的第 3 根上）
        assertEquals(93.0, candles.get(1).high(), 1e-9);
        assertEquals(LocalDate.of(2022, 9, 5), candles.get(candles.size() - 1).date());
        assertEquals(92.5, candles.get(candles.size() - 1).close(), 1e-9);
    }

    @Test
    void rejectsNon200Status() {
        var provider = new YahooFinanceProvider(new FakeTransport(List.of(429), "{}"));
        var e = assertThrows(HttpFetchException.class, () ->
                provider.fetchDaily("NVDA", LocalDate.of(2022, 1, 1), LocalDate.of(2022, 1, 31)));
        assertEquals(HttpFetchException.Type.RATE_LIMITED, e.type());
    }

    @Test
    void surfacesYahooErrorFieldAsClientError() {
        String body = """
                {"chart":{"result":null,"error":{"code":"Not Found",
                  "description":"No data found, symbol may be delisted"}}}""";
        var provider = new YahooFinanceProvider(new FakeTransport(List.of(200), body));
        var e = assertThrows(HttpFetchException.class, () ->
                provider.fetchDaily("BAD", LocalDate.of(2022, 1, 1), LocalDate.of(2022, 1, 31)));
        assertEquals(HttpFetchException.Type.CLIENT, e.type());
        assertTrue(e.getMessage().contains("No data found"));
    }

    @Test
    void rejectsInvalidJsonAsParseError() {
        var provider = new YahooFinanceProvider(new FakeTransport(List.of(200), "<html>not json</html>"));
        var e = assertThrows(HttpFetchException.class, () ->
                provider.fetchDaily("NVDA", LocalDate.of(2022, 1, 1), LocalDate.of(2022, 1, 31)));
        assertEquals(HttpFetchException.Type.PARSE, e.type());
    }
}
