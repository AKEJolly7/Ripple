package com.ripple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ripple.dataprovider.*;
import com.ripple.dataprovider.api.MarketDataProvider;
import com.ripple.domain.NewsItem;
import com.ripple.domain.Ohlcv;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 数据抓取 CLI：--symbol NVDA --years 5 [--keyword 关键词] [--out work]
 * 行情走 Yahoo 主源 + 新浪/Binance 兜底（Yahoo 403/网络失败时自动降级），
 * 把 OHLCV 与事件 JSON（含来源标注）落到 work/ 目录，控制台输出条数 + 首末日期。
 */
public final class DataFetchMain {

    private static final Logger log = LoggerFactory.getLogger(DataFetchMain.class);
    private static final ZoneId MARKET_TZ = ZoneId.of("America/New_York");

    /**
     * 无 --keyword 时的默认检索词（symbol → 行业惯用词）。
     */
    private static final Map<String, String> DEFAULT_KEYWORDS = Map.of(
            "NVDA", "Nvidia",
            "GLD", "gold price",
            "BTC-USD", "Bitcoin");

    /**
     * 行情抓取结果（含实际命中的数据源，用于落盘溯源）。
     */
    public record MarketData(String source, List<Ohlcv> candles) {
    }

    public static void main(String[] args) {
        try {
            System.exit(run(args));
        } catch (Exception e) {
            log.error("抓取失败: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    static int run(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        String symbol = opts.get("symbol");
        if (Strings.isBlank(symbol)) {
            System.err.println("缺少 --symbol 参数。用法: --symbol NVDA --years 5 [--keyword K] [--out work]");
            return 2;
        }
        symbol = symbol.toUpperCase(Locale.ROOT);
        int years = Integer.parseInt(opts.getOrDefault("years", "5"));
        String keyword = opts.getOrDefault("keyword",
                DEFAULT_KEYWORDS.getOrDefault(symbol, symbol));
        Path outDir = Path.of(opts.getOrDefault("out", "work"));

        LocalDate end = LocalDate.now(MARKET_TZ);
        LocalDate start = end.minusYears(years);
        log.info("开始抓取: symbol={}, years={}, keyword={}, 输出目录={}", symbol, years, keyword, outDir);

        MarketData market = fetchDailyWithFallback(symbol, start, end);
        List<NewsItem> news = new HackerNewsProvider().search(keyword, start, end);

        Files.createDirectories(outDir);
        ObjectMapper mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        var writer = mapper.writerWithDefaultPrettyPrinter();

        Map<String, Object> ohlcvDoc = new HashMap<>();
        ohlcvDoc.put("symbol", symbol);
        ohlcvDoc.put("source", market.source());
        ohlcvDoc.put("windowStart", start);
        ohlcvDoc.put("windowEnd", end);
        ohlcvDoc.put("candles", market.candles());
        Map<String, Object> newsDoc = new HashMap<>();
        newsDoc.put("keyword", keyword);
        newsDoc.put("provider", "HackerNewsProvider");
        newsDoc.put("items", news);

        Path ohlcvFile = outDir.resolve(symbol + "_ohlcv.json");
        Path newsFile = outDir.resolve(symbol + "_news.json");
        Files.writeString(ohlcvFile, writer.writeValueAsString(ohlcvDoc));
        Files.writeString(newsFile, writer.writeValueAsString(newsDoc));

        // 验收要求的输出：条数 + 首末日期（含实际数据源）
        System.out.printf("OHLCV: symbol=%s, source=%s, 条数=%d, 首=%s, 末=%s, 落盘=%s%n",
                symbol, market.source(), market.candles().size(),
                market.candles().isEmpty() ? "-" : market.candles().get(0).date(),
                market.candles().isEmpty() ? "-" : market.candles().get(market.candles().size() - 1).date(),
                ohlcvFile);
        System.out.printf("News:  keyword=\"%s\", 条数=%d, 首=%s, 末=%s, 落盘=%s%n",
                keyword, news.size(),
                news.isEmpty() ? "-" : news.get(0).date(),
                news.isEmpty() ? "-" : news.get(news.size() - 1).date(),
                newsFile);
        return 0;
    }

    /**
     * Yahoo 主源，失败时按标的类型降级：BTC-USD → Binance → Gate.io，其余 → 新浪。供 toolkit/agent 层复用。
     */
    public static MarketData fetchDailyWithFallback(String symbol, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException {
        MarketDataProvider primary = new YahooFinanceProvider();
        try {
            return new MarketData("YahooFinanceProvider", primary.fetchDaily(symbol, start, end));
        } catch (HttpFetchException e) {
            log.warn("Yahoo 拉取失败（{}: {}），切换兜底数据源", e.type(), e.getMessage());
            if (symbol.equals("BTC-USD")) {
                try {
                    return new MarketData("BinanceProvider",
                            new BinanceProvider().fetchDaily(symbol, start, end));
                } catch (HttpFetchException e2) {
                    log.warn("Binance 拉取失败（{}: {}），切换 Gate.io", e2.type(), e2.getMessage());
                    return new MarketData("GateIoProvider",
                            new com.ripple.dataprovider.GateIoProvider().fetchDaily(symbol, start, end));
                }
            }
            return new MarketData("SinaFinanceProvider",
                    new SinaFinanceProvider().fetchDaily(symbol, start, end));
        }
    }

    private static Map<String, String> parseArgs(String[] args) {
        Map<String, String> opts = new HashMap<>();
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if (a.startsWith("--")) {
                String key = a.substring(2);
                if (i + 1 < args.length && !args[i + 1].startsWith("--")) {
                    opts.put(key, args[++i]);
                } else {
                    opts.put(key, "true");
                }
            }
        }
        return opts;
    }

    private DataFetchMain() {
    }
}
