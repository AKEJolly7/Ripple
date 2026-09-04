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
 * Gate.io 日线二级兜底数据源（Binance 不可达时）。
 * https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair=BTC_USDT&interval=1d&from=&to=&limit=1000
 * 行结构 [tsSec, quoteVolume, open, high, low, close, baseVolume, ...]，from/to 为秒。
 * 注意：除 limit=1000 外，from/to 的时间跨度本身也须 ≤1000 天（超过返回 400
 * "Candlestick range too broad"），故按 SEGMENT_DAYS=999 天分段请求。
 */
public final class GateIoProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(GateIoProvider.class);

    /**
     * 单段最大天数（API 限制 1000 天/请求，留 1 天余量）。
     */
    private static final long SEGMENT_DAYS = 999;

    private final HttpTransport transport;
    private final int pageSize;

    public GateIoProvider() {
        this(new ResilientHttpTransport(), 1000);
    }

    public GateIoProvider(HttpTransport transport, int pageSize) {
        this.transport = transport;
        this.pageSize = pageSize;
    }

    @Override
    public List<Ohlcv> fetchDaily(String symbol, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException {
        String pair = symbol.toUpperCase().replace("-USD", "_USDT");   // BTC-USD → BTC_USDT
        ObjectMapper mapper = new ObjectMapper();
        List<Ohlcv> out = new ArrayList<>();
        LocalDate segStart = start;
        while (!segStart.isAfter(end)) {
            LocalDate segEnd = segStart.plusDays(SEGMENT_DAYS - 1);
            if (segEnd.isAfter(end)) {
                segEnd = end;
            }
            fetchSegment(pair, segStart, segEnd, mapper, out);
            segStart = segEnd.plusDays(1);
        }
        log.info("Gate.io {} [{} ~ {}] 分段拉取 {} 根日 K", pair, start, end, out.size());
        return out;
    }

    /**
     * 拉取一段（≤999 天）：limit=1000 足以覆盖整段，无需段内翻页。
     */
    private void fetchSegment(String pair, LocalDate segStart, LocalDate segEnd,
                              ObjectMapper mapper, List<Ohlcv> out)
            throws HttpFetchException, InterruptedException {
        long fromSec = segStart.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long toSec = segEnd.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond() - 1;
        URI uri = URI.create("https://api.gateio.ws/api/v4/spot/candlesticks?currency_pair="
                + pair + "&interval=1d&from=" + fromSec + "&to=" + toSec
                + "&limit=" + pageSize);
        HttpTransport.Response resp = transport.get(uri);
        if (resp.status() != 200) {
            throw HttpFetchException.ofStatus(resp.status());
        }
        JsonNode rows;
        try {
            rows = mapper.readTree(resp.body());
        } catch (Exception e) {
            throw new HttpFetchException(HttpFetchException.Type.PARSE,
                    "Gate.io 响应不是合法 JSON", e);
        }
        for (JsonNode row : rows) {
            long ts = row.get(0).asLong();
            out.add(new Ohlcv(
                    Instant.ofEpochSecond(ts).atZone(ZoneOffset.UTC).toLocalDate(),
                    row.get(2).asDouble(), row.get(3).asDouble(),
                    row.get(4).asDouble(), row.get(5).asDouble(),
                    Math.round(row.get(6).asDouble())));
        }
    }
}
