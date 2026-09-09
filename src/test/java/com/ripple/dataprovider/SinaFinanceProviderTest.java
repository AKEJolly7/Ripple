package com.ripple.dataprovider;

import com.ripple.domain.Ohlcv;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * 用 mock 的新浪 JSONP 响应验证解析、窗口过滤与拆股前复权，不访问真实网络。
 */
class SinaFinanceProviderTest {

    // 2021-07-19（4:1 拆股前，窗口外）、2021-09-02（10:1 拆股前，窗口内）、
    // 2024-06-07（10:1 拆股前最后一天）、2024-06-10（除权日，不调整）
    private static final String BODY = """
            /*<script>location.href='//sina.com';</script>*/
            var t=([
            {"d":"2021-07-19","o":"800.0","h":"810.0","l":"795.0","c":"805.0","v":"1000","a":"0"},
            {"d":"2021-09-02","o":"1200.0","h":"1210.0","l":"1195.0","c":"1205.0","v":"2000","a":"0"},
            {"d":"2024-06-07","o":"1197.7","h":"1216.92","l":"1180.22","c":"1208.88","v":"41238499","a":"0"},
            {"d":"2024-06-10","o":"120.37","h":"123.09","l":"117.01","c":"121.79","v":"314162647","a":"0"}])""";

    @Test
    void stripsJsonpFiltersWindowAndAdjustsSplits() throws Exception {
        var provider = new SinaFinanceProvider(new FakeTransport(List.of(200), BODY));

        List<Ohlcv> candles = provider.fetchDaily("NVDA",
                LocalDate.of(2021, 9, 1), LocalDate.of(2026, 9, 1));

        assertEquals(3, candles.size()); // 2021-07-19 在窗口外被过滤

        // 2021-09-02：10:1 拆股前 → 价格 ÷10（1205→120.5），成交量 ×10
        Ohlcv sep = candles.get(0);
        assertEquals(LocalDate.of(2021, 9, 2), sep.date());
        assertEquals(120.5, sep.close(), 1e-9);
        assertEquals(120.0, sep.open(), 1e-9);
        assertEquals(20000L, sep.volume());

        // 2024-06-07：除权日前一天 → 1208.88÷10=120.888
        Ohlcv pre = candles.get(1);
        assertEquals(120.888, pre.close(), 1e-9);
        assertEquals(412384990L, pre.volume());

        // 2024-06-10：除权日本身不调整
        Ohlcv split = candles.get(2);
        assertEquals(121.79, split.close(), 1e-9);
        assertEquals(314162647L, split.volume());
    }

    @Test
    void noSplitTableMeansRawPrices() throws Exception {
        // GLD 无拆股表 → 原样返回
        String body = """
                var t=([{"d":"2024-01-02","o":"390.0","h":"392.0","l":"389.0","c":"391.0","v":"5000","a":"0"}])""";
        var provider = new SinaFinanceProvider(new FakeTransport(List.of(200), body));

        List<Ohlcv> candles = provider.fetchDaily("GLD",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31));

        assertEquals(1, candles.size());
        assertEquals(391.0, candles.get(0).close(), 1e-9);
    }

    @Test
    void unknownSymbolSurfacesAsClientError() {
        var provider = new SinaFinanceProvider(new FakeTransport(List.of(200), "var t=([])"));
        var e = assertThrows(HttpFetchException.class, () ->
                provider.fetchDaily("NOPE", LocalDate.of(2024, 1, 1), LocalDate.of(2024, 1, 31)));
        assertEquals(HttpFetchException.Type.CLIENT, e.type());
    }
}
