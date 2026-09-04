package com.ripple.dataprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.dataprovider.api.HttpTransport;
import com.ripple.dataprovider.api.MarketDataProvider;
import com.ripple.domain.Ohlcv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

/**
 * Yahoo chart API v8 直连实现。
 * https://query1.finance.yahoo.com/v8/finance/chart/{symbol}?period1=&period2=&interval=1d&includeAdjustedClose=true
 * <p>
 * 价格处理：Yahoo 返回的 close 为未复权价（NVDA 2024-06 10:1 拆股会造成序列断裂），
 * 因此用 adjclose/close 因子等比缩放 O/H/L/C，输出前复权序列；volume 保持原值。
 * 假日等造成的 null 元素直接跳过。
 */
public final class YahooFinanceProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(YahooFinanceProvider.class);
    private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

    private final HttpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    public YahooFinanceProvider() {
        this(new ResilientHttpTransport());
    }

    public YahooFinanceProvider(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<Ohlcv> fetchDaily(String symbol, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException {
        long period1 = start.atStartOfDay(MARKET_TZ).toEpochSecond();
        long period2 = end.plusDays(1).atStartOfDay(MARKET_TZ).toEpochSecond();
        String enc = URLEncoder.encode(symbol, StandardCharsets.UTF_8);
        URI uri = URI.create("https://query1.finance.yahoo.com/v8/finance/chart/" + enc
                + "?period1=" + period1 + "&period2=" + period2
                + "&interval=1d&includeAdjustedClose=true");

        HttpTransport.Response resp = transport.get(uri);
        if (resp.status() != 200) {
            throw HttpFetchException.ofStatus(resp.status());
        }
        List<Ohlcv> candles = parse(resp.body());
        log.info("Yahoo {} [{} ~ {}] 拉取 {} 根日 K", symbol, start, end, candles.size());
        return candles;
    }

    private List<Ohlcv> parse(String body) throws HttpFetchException {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new HttpFetchException(HttpFetchException.Type.PARSE, "响应不是合法 JSON", e);
        }
        JsonNode chart = root.path("chart");
        JsonNode error = chart.path("error");
        if (!error.isMissingNode() && !error.isNull()) {
            throw new HttpFetchException(HttpFetchException.Type.CLIENT,
                    "Yahoo 返回错误: " + error.path("description").asText(error.toString()));
        }
        JsonNode result = chart.path("result");
        if (!result.isArray() || result.isEmpty()) {
            throw new HttpFetchException(HttpFetchException.Type.PARSE, "chart.result 缺失");
        }
        JsonNode node = result.get(0);
        JsonNode ts = node.path("timestamp");
        JsonNode quote = node.path("indicators").path("quote").path(0);
        JsonNode closes = quote.path("close");
        JsonNode opens = quote.path("open");
        JsonNode highs = quote.path("high");
        JsonNode lows = quote.path("low");
        JsonNode volumes = quote.path("volume");
        JsonNode adj = node.path("indicators").path("adjclose").path(0).path("adjclose");

        List<Ohlcv> out = new ArrayList<>(ts.size());
        for (int i = 0; i < ts.size(); i++) {
            JsonNode closeNode = closes.get(i);
            if (closeNode == null || closeNode.isNull()) {
                continue; // 停牌/假日占位
            }
            double close = closeNode.asDouble();
            double factor = 1.0;
            JsonNode adjNode = adj.get(i);
            if (adjNode != null && !adjNode.isNull() && close > 0) {
                factor = adjNode.asDouble() / close;
            }
            double open = orDefault(opens.get(i), close) * factor;
            double high = orDefault(highs.get(i), close) * factor;
            double low = orDefault(lows.get(i), close) * factor;
            long volume = volumeOrZero(volumes.get(i));
            LocalDate date = Instant.ofEpochSecond(ts.get(i).asLong()).atZone(MARKET_TZ).toLocalDate();
            out.add(new Ohlcv(date, open, high, low, close * factor, volume));
        }
        return out;
    }

    private static double orDefault(JsonNode n, double fallback) {
        return (n == null || n.isNull()) ? fallback : n.asDouble();
    }

    private static long volumeOrZero(JsonNode n) {
        return (n == null || n.isNull()) ? 0L : (long) n.asDouble();
    }
}
