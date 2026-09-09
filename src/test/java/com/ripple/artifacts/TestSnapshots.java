package com.ripple.artifacts;

import java.time.LocalDate;
import java.util.*;

/**
 * 测试共享：最小可渲染 Snapshot（3 资产 × 2 交易日，满足 Excel/PPT/Word 生成所需字段）。
 */
final class TestSnapshots {

    private TestSnapshots() {
    }

    static ExcelWriter.Snapshot minimal() {
        List<String> order = List.of("GLD（黄金ETF）", "BTC-USD（比特币）", "SPY（美股基准）");
        LocalDate d1 = LocalDate.of(2024, 1, 1);
        LocalDate d2 = LocalDate.of(2024, 6, 30);
        Map<String, PortfolioStats.Metrics> metrics = new LinkedHashMap<>();
        Map<String, List<PortfolioStats.DrawdownEpisode>> drawdowns = new LinkedHashMap<>();
        Map<String, Map<String, Double>> correlations = new LinkedHashMap<>();
        Map<String, List<Double>> closes = new LinkedHashMap<>();
        List<Double> prices = List.of(100.0, 110.0);
        for (String a : order) {
            metrics.put(a, new PortfolioStats.Metrics(a, 0.10, 0.20, 0.30, 0.5, 182, d1, d2));
            drawdowns.put(a, List.of(new PortfolioStats.DrawdownEpisode(a, d1, 100, d2, 90, 10.0, 180)));
            Map<String, Double> row = new LinkedHashMap<>();
            for (String b : order) {
                row.put(b, a.equals(b) ? 1.0 : 0.1);
            }
            correlations.put(a, row);
            closes.put(a, prices);
        }
        List<PortfolioStats.Metrics> combos = List.of(
                new PortfolioStats.Metrics("100% 金", 0.19, 0.19, 0.26, 1.0, 182, d1, d2),
                new PortfolioStats.Metrics("金 60% / 币 40%（可配权重）", 0.19, 0.25, 0.43, 0.78, 182, d1, d2));
        return new ExcelWriter.Snapshot(order, metrics, drawdowns, correlations, combos,
                List.of(d1, d2), closes, Map.of());
    }
}
