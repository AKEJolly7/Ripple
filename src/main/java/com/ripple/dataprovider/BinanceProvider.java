package com.ripple.dataprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.dataprovider.api.HttpTransport;
import com.ripple.dataprovider.api.MarketDataProvider;
import com.ripple.domain.Ohlcv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * Binance 日线兜底数据源（BTC-USD 专用）。
 * https://api.binance.com/api/v3/klines?symbol=BTCUSDT&interval=1d&startTime=&endTime=&limit=1000
 * <p>
 * 单页上限 1000 根，按 startTime 游标翻页拉全量；行结构
 * [openTimeMs, open, high, low, close, volume, closeTimeMs, ...]。BTC 无拆股无分红，无需复权。
 */
public final class BinanceProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(BinanceProvider.class);

    private final HttpTransport transport;
    private final int pageSize;

    public BinanceProvider() {
        this(new ResilientHttpTransport(), 1000);
    }

    public BinanceProvider(HttpTransport transport, int pageSize) {
        this.transport = transport;
        this.pageSize = pageSize;
    }

    @Override
    public List<Ohlcv> fetchDaily(String symbol, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException {
        // BTC-USD → BTCUSDT（USDT 近似 USD，报告口径中注明）
        String pair = symbol.toUpperCase().replace("-USD", "USDT");
        long fromMs = start.atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1000;
        long endMs = (end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() * 1000) - 1;

        ObjectMapper mapper = new ObjectMapper();
        List<Ohlcv> out = new ArrayList<>();
        while (true) {
            URI uri = URI.create("https://api.binance.com/api/v3/klines?symbol=" + pair
                    + "&interval=1d&startTime=" + fromMs + "&endTime=" + endMs
                    + "&limit=" + pageSize);
            HttpTransport.Response resp = transport.get(uri);
            if (resp.status() != 200) {
                throw HttpFetchException.ofStatus(resp.status());
            }
            JsonNode rows;
            try {
                rows = mapper.readTree(resp.body());
            } catch (Exception e) {
                throw new HttpFetchException(HttpFetchException.Type.PARSE, "Binance 响应不是合法 JSON", e);
            }
            long lastOpenMs = -1;
            for (JsonNode row : rows) {
                long openMs = row.get(0).asLong();
                out.add(new Ohlcv(
                        Instant.ofEpochMilli(openMs).atZone(ZoneOffset.UTC).toLocalDate(),
                        row.get(1).asDouble(), row.get(2).asDouble(),
                        row.get(3).asDouble(), row.get(4).asDouble(),
                        Math.round(row.get(5).asDouble())));
                lastOpenMs = openMs;
            }
            if (rows.size() < pageSize || lastOpenMs < 0) {
                break; // 尾页
            }
            fromMs = lastOpenMs + 1;
        }
        log.info("Binance {} [{} ~ {}] 翻页拉取 {} 根日 K", pair, start, end, out.size());
        return out;
    }
}
