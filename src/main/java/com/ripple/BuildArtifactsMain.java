package com.ripple;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.artifacts.*;
import com.ripple.domain.Ohlcv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 三件套生成 CLI（任务 B）：
 * BuildArtifactsMain [--in work] [--out artifacts] [--gold-weight 0.6] [--btc-weight 0.4]
 * 一条命令产出 artifacts/ 下：gold-btc-backtest.xlsx / gold-btc-framework.pptx / gold-btc-strategy.docx。
 * 数据复用 R1 通道（work/ 检查点，缺 SPY 时自动抓取），指标复用 commons-math3 统计技能。
 */
public final class BuildArtifactsMain {

    private static final Logger log = LoggerFactory.getLogger(BuildArtifactsMain.class);

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .findAndRegisterModules();

    public static void main(String[] args) throws Exception {
        Map<String, String> opts = parseArgs(args);
        Path workDir = Path.of(opts.getOrDefault("in", "work"));
        Path outDir = Path.of(opts.getOrDefault("out", "artifacts"));
        double goldW = Double.parseDouble(opts.getOrDefault("gold-weight", "0.6"));
        double btcW = Double.parseDouble(opts.getOrDefault("btc-weight", "0.4"));
        if (Math.abs(goldW + btcW - 1.0) > 1e-9) {
            log.error("--gold-weight 与 --btc-weight 之和须为 1（当前 {} + {}）", goldW, btcW);
            System.exit(2);
        }

        // 1) 数据加载（缺 SPY 自动补抓，复用 R1 降级链）
        record Asset(String name, String symbol, List<Ohlcv> candles, String source) {
        }
        List<Asset> assets = new ArrayList<>();
        for (String[] spec : new String[][]{
                {"GLD（黄金ETF）", "GLD"}, {"BTC-USD（比特币）", "BTC-USD"}, {"SPY（美股基准）", "SPY"}}) {
            String name = spec[0];
            String symbol = spec[1];
            Path file = workDir.resolve(symbol + "_ohlcv.json");
            if (!Files.exists(file)) {
                log.info("{} 检查点缺失，自动抓取…", symbol);
                var md = DataFetchMain.fetchDailyWithFallback(symbol,
                        LocalDate.now().minusYears(5), LocalDate.now());
                Files.createDirectories(workDir);
                Map<String, Object> doc = new HashMap<>();
                doc.put("symbol", symbol);
                doc.put("source", md.source());
                doc.put("candles", md.candles());
                MAPPER.writerWithDefaultPrettyPrinter().writeValue(file.toFile(), doc);
            }
            AnalysisMain.JsonDoc doc = MAPPER.readValue(file.toFile(), AnalysisMain.JsonDoc.class);
            assets.add(new Asset(name, symbol, doc.candles(), doc.source()));
        }

        // 2) 统计（复用技能层）
        PortfolioStats stats = new PortfolioStats();
        for (Asset a : assets) {
            stats.add(a.name(), a.candles());
        }
        Map<String, PortfolioStats.Metrics> metrics = new HashMap<>();
        Map<String, List<PortfolioStats.DrawdownEpisode>> drawdowns = new HashMap<>();
        Map<String, String> sources = new HashMap<>();
        for (Asset a : assets) {
            metrics.put(a.name(), stats.metricsOf(a.name(), a.candles()));
            drawdowns.put(a.name(), stats.topDrawdowns(a.name(), a.candles(), 5));
            sources.put(a.name(), a.source());
        }
        var correlations = stats.correlationMatrix();

        // 3) 组合情景（含可配权重）
        List<PortfolioStats.Metrics> combos = List.of(
                stats.comboMetrics(new PortfolioStats.Weighting("100% 金", 1, 0, 0)),
                stats.comboMetrics(new PortfolioStats.Weighting("100% 币", 0, 1, 0)),
                stats.comboMetrics(new PortfolioStats.Weighting(
                        "金 %.0f%% / 币 %.0f%%（可配权重）".formatted(goldW * 100, btcW * 100), goldW, btcW, 0)),
                stats.comboMetrics(new PortfolioStats.Weighting("金 50% / 币 50%", 0.5, 0.5, 0)),
                stats.comboMetrics(new PortfolioStats.Weighting("金 40% / 币 60%", 0.4, 0.6, 0)),
                stats.comboMetrics(new PortfolioStats.Weighting("股 60% / 金 40%（经典 60/40）", 0.4, 0, 0.6)));

        var closes = stats.alignedCloses();
        ExcelWriter.Snapshot snap = new ExcelWriter.Snapshot(
                assets.stream().map(Asset::name).toList(), metrics, drawdowns, correlations,
                combos, stats.alignedDates(), closes, sources);

        // 4) 图表 PNG（Java2D 静态图，见 ChartPng 取舍注释）
        Files.createDirectories(outDir);
        Path chartPng = outDir.resolve("gold-btc-normalized.png");
        Map<String, List<Double>> norm = new HashMap<>();
        for (var e : closes.entrySet()) {
            List<Double> l = new ArrayList<>();
            double base = e.getValue().getFirst();
            for (double v : e.getValue()) {
                l.add(v / base * 100);
            }
            norm.put(e.getKey(), l);
        }
        ChartPng.normalizedPrice(chartPng, stats.alignedDates(), norm,
                "黄金 vs 比特币 vs SPY（归一化，起点=100）");

        // 5) 三件套
        Path xlsx = new ExcelWriter().write(snap, outDir.resolve("gold-btc-backtest.xlsx"));
        Path pptx = new PptxWriter().write(snap, chartPng, outDir.resolve("gold-btc-framework.pptx"));
        Path docx = new DocxWriter().write(snap, outDir.resolve("gold-btc-strategy.docx"));

        // 6) 控制台摘要（验收对照）
        System.out.println("=== 黄金 vs 比特币 回测摘要（" + stats.alignedDates().getFirst()
                + " ~ " + stats.alignedDates().getLast() + "，交集 " + stats.alignedDates().size() + " 日）===");
        for (String a : snap.assetOrder()) {
            var m = metrics.get(a);
            System.out.printf("%-14s CAGR %.2f%% | 波动 %.2f%% | 最大回撤 %.2f%% | 夏普 %.4f%n",
                    a, m.cagr() * 100, m.annVol() * 100, m.maxDrawdown(), m.sharpe());
        }
        System.out.println("相关矩阵（Pearson，日收益率）:");
        for (var e : correlations.entrySet()) {
            System.out.printf("  %-14s %s%n", e.getKey(), e.getValue());
        }
        System.out.println("组合情景:");
        for (var m : combos) {
            System.out.printf("  %-24s CAGR %.2f%% | 波动 %.2f%% | 回撤 %.2f%% | 夏普 %.4f%n",
                    m.name(), m.cagr() * 100, m.annVol() * 100, m.maxDrawdown(), m.sharpe());
        }
        System.out.printf("产物: %s | %s | %s | %s%n", xlsx, pptx, docx, chartPng);
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

    private BuildArtifactsMain() {
    }
}
