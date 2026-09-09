package com.ripple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ripple.analysis.PivotDetector;
import com.ripple.analysis.model.AnalysisConfig;
import com.ripple.analysis.model.AnalysisResult;
import com.ripple.domain.InflectionPoint;
import com.ripple.domain.Ohlcv;
import com.ripple.domain.Segment;
import com.ripple.domain.enums.PivotType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/**
 * 技术分析 CLI：--symbol NVDA [--in work]
 * 复用 DataFetchMain 抓取的 work/{symbol}_ohlcv.json，打印拐点与趋势段（确定性，无 LLM）。
 */
public final class AnalysisMain {

    private static final Logger log = LoggerFactory.getLogger(AnalysisMain.class);

    public static void main(String[] args) {
        Map<String, String> opts = parseArgs(args);
        String symbol = opts.getOrDefault("symbol", "NVDA").toUpperCase(Locale.ROOT);
        Path file = Path.of(opts.getOrDefault("in", "work")).resolve(symbol + "_ohlcv.json");
        if (!Files.exists(file)) {
            log.error("未找到 {}，请先运行 DataFetchMain --symbol {}", file, symbol);
            System.exit(2);
        }
        try {
            List<Ohlcv> candles = loadCandles(file);
            AnalysisConfig cfg = AnalysisConfig.load();
            AnalysisResult result = new PivotDetector().analyze(candles, cfg);
            print(symbol, result);
        } catch (Exception e) {
            log.error("分析失败: {}", e.getMessage(), e);
            System.exit(1);
        }
    }

    /**
     * 读取 work/ 落盘的 OHLCV 文档（DataFetchMain 产物，candles 字段为 Ohlcv 数组）。
     */
    public static List<Ohlcv> loadCandles(Path file) throws Exception {
        ObjectMapper mapper = new ObjectMapper().registerModule(new JavaTimeModule());
        JsonDoc doc = mapper.readValue(file.toFile(), JsonDoc.class);
        if (doc.candles() == null || doc.candles().isEmpty()) {
            throw new IllegalStateException(file + " 中无 K 线数据");
        }
        return doc.candles();
    }

    /**
     * work/ 文档结构（只取需要的字段，忽略 windowStart 等其余字段）。
     */
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
    public record JsonDoc(String symbol, String source, List<Ohlcv> candles) {
    }

    private static void print(String symbol, AnalysisResult r) {
        List<Ohlcv> c = r.candles();
        int last = c.size() - 1;

        System.out.printf("=== %s 技术分析（%s ~ %s，%d 根日 K）===%n",
                symbol, c.get(0).date(), c.get(c.size() - 1).date(), c.size());
        System.out.printf("最新收盘 %.2f | MA5 %.2f | MA20 %.2f | MA60 %.2f | RSI14 %.1f | 20日波动率 %.2f%%%n",
                c.get(c.size() - 1).close(), r.ma5()[last], r.ma20()[last], r.ma60()[last],
                r.rsi14()[last], r.volatility20()[last]);

        System.out.printf("%n--- 趋势段（%d 段）---%n", r.segments().size());
        System.out.println("起始日期      结束日期      标签   起价     末价     净涨跌%   最大回撤%");
        for (Segment s : r.segments()) {
            System.out.printf("%-12s  %-12s  %-4s  %8.2f  %8.2f  %+7.2f  %7.2f%n",
                    s.startDate(), s.endDate(), s.label().label(),
                    s.startPrice(), s.endPrice(), s.netChangePct(), s.maxDrawdownPct());
        }

        System.out.printf("%n--- 拐点（%d 个）---%n", r.pivots().size());
        System.out.println("日期          类型        当日涨跌%  放量  候选窗口");
        for (InflectionPoint p : r.pivots()) {
            System.out.printf("%-12s  %-10s  %+8.2f  %-4s  %s ~ %s%n",
                    p.date(), p.type().label(), p.dayChangePct(),
                    p.volumeSpike() ? "是" : "-",
                    p.windowStart(), p.windowEnd());
        }

        Map<PivotType, Long> counts = new HashMap<>();
        for (InflectionPoint p : r.pivots()) {
            counts.merge(p.type(), 1L, Long::sum);
        }
        System.out.printf("%n合计: 拐点 %d 个（%s），趋势段 %d 段%n",
                r.pivots().size(),
                Arrays.stream(PivotType.values())
                        .map(t -> t.label() + " " + counts.getOrDefault(t, 0L))
                        .reduce((a, b) -> a + "，" + b).orElse(""),
                r.segments().size());
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

    private AnalysisMain() {
    }
}
