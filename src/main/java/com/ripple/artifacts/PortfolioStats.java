package com.ripple.artifacts;

import com.ripple.domain.Ohlcv;
import org.apache.commons.math3.stat.correlation.PearsonsCorrelation;
import org.apache.commons.math3.stat.descriptive.DescriptiveStatistics;

import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 组合统计技能（确定性，commons-math3）：年化收益（CAGR，按日历时间）、年化波动、
 * 最大回撤、夏普（rf=0，口径注明）、Pearson 相关与任意权重组合。
 * 周期化口径：各资产每年交易日数按实际样本密度计算（BTC 7×24 ≈ 365/年，GLD/SPY ≈ 252/年）。
 */
public final class PortfolioStats {

    /**
     * 单资产指标。
     */
    public record Metrics(String name, double cagr, double annVol, double maxDrawdown,
                          double sharpe, int days, LocalDate first, LocalDate last) {
    }

    /**
     * 回撤事件（峰到谷再到修复或不修复）。
     */
    public record DrawdownEpisode(String name, LocalDate peakDate, double peak,
                                  LocalDate troughDate, double trough,
                                  double depthPct, long peakToTroughDays) {
    }

    /**
     * 组合权重。
     */
    public record Weighting(String label, double goldW, double btcW, double spyW) {
    }

    private final Map<String, Map<LocalDate, Double>> closes = new LinkedHashMap<>();
    private final List<LocalDate> alignedDates = new ArrayList<>();
    private final Map<String, double[]> returns = new LinkedHashMap<>();

    /**
     * 载入资产（顺序即表格列序）。
     */
    public PortfolioStats add(String name, List<Ohlcv> candles) {
        Map<LocalDate, Double> m = new LinkedHashMap<>();
        for (Ohlcv c : candles) {
            m.put(c.date(), c.close());
        }
        closes.put(name, m);
        recomputeAlignment();
        return this;
    }

    /**
     * 按全部资产日期交集对齐，并计算对齐后日收益率序列。
     */
    private void recomputeAlignment() {
        alignedDates.clear();
        if (closes.isEmpty()) {
            return;
        }
        Map<LocalDate, Double> first = closes.values().iterator().next();
        List<LocalDate> common = new ArrayList<>(first.keySet());
        for (Map<LocalDate, Double> m : closes.values()) {
            common.retainAll(m.keySet());
        }
        common.sort(LocalDate::compareTo);
        alignedDates.addAll(common);
        returns.clear();
        for (var e : closes.entrySet()) {
            double[] r = new double[alignedDates.size()];
            for (int i = 1; i < alignedDates.size(); i++) {
                double a = e.getValue().get(alignedDates.get(i - 1));
                double b = e.getValue().get(alignedDates.get(i));
                r[i] = b / a - 1;
            }
            returns.put(e.getKey(), r);
        }
    }

    public List<LocalDate> alignedDates() {
        return List.copyOf(alignedDates);
    }

    /**
     * 对齐后收盘价（列序 = add 顺序）。
     */
    public Map<String, List<Double>> alignedCloses() {
        Map<String, List<Double>> out = new LinkedHashMap<>();
        for (var e : closes.entrySet()) {
            List<Double> l = new ArrayList<>();
            for (LocalDate d : alignedDates) {
                l.add(e.getValue().get(d));
            }
            out.put(e.getKey(), l);
        }
        return out;
    }

    /**
     * 单资产指标（按各自完整序列，不受交集截断；交集仅用于相关性/组合）。
     */
    public Metrics metricsOf(String name, List<Ohlcv> candles) {
        int n = candles.size();
        double start = candles.getFirst().close();
        double end = candles.getLast().close();
        double years = ChronoUnit.DAYS.between(candles.getFirst().date(),
                candles.getLast().date()) / 365.25;
        double cagr = Math.pow(end / start, 1 / Math.max(years, 1e-9)) - 1;

        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (int i = 1; i < n; i++) {
            ds.addValue(candles.get(i).close() / candles.get(i - 1).close() - 1);
        }
        double periodsPerYear = n / Math.max(years, 1e-9);
        double annVol = ds.getStandardDeviation() * Math.sqrt(periodsPerYear);
        double mdd = maxDrawdownOf(candles);
        double sharpe = annVol == 0 ? 0 : cagr / annVol;
        return new Metrics(name, cagr, annVol, mdd, sharpe, n,
                candles.getFirst().date(), candles.getLast().date());
    }

    /**
     * 最大回撤（收盘价口径，%）。
     */
    public double maxDrawdownOf(List<Ohlcv> candles) {
        double peak = Double.NEGATIVE_INFINITY;
        double mdd = 0;
        for (Ohlcv c : candles) {
            peak = Math.max(peak, c.close());
            mdd = Math.max(mdd, (peak - c.close()) / peak);
        }
        return mdd * 100;
    }

    /**
     * 回撤事件 Top N（深度排序；未修复事件以当前值为谷）。
     */
    public List<DrawdownEpisode> topDrawdowns(String name, List<Ohlcv> candles, int topN) {
        List<DrawdownEpisode> episodes = new ArrayList<>();
        LocalDate peakDate = null;
        double peak = Double.NEGATIVE_INFINITY;
        LocalDate troughDate = null;
        double trough = Double.POSITIVE_INFINITY;
        for (Ohlcv c : candles) {
            if (c.close() >= peak) {
                if (peakDate != null && trough < peak) {
                    episodes.add(new DrawdownEpisode(name, peakDate, peak,
                            troughDate, trough, (peak - trough) / peak * 100,
                            ChronoUnit.DAYS.between(peakDate, troughDate)));
                }
                peak = c.close();
                peakDate = c.date();
                trough = peak;
                troughDate = peakDate;
            } else if (c.close() < trough) {
                trough = c.close();
                troughDate = c.date();
            }
        }
        if (trough < peak) {   // 尾部未修复事件
            episodes.add(new DrawdownEpisode(name, peakDate, peak, troughDate, trough,
                    (peak - trough) / peak * 100, ChronoUnit.DAYS.between(peakDate, troughDate)));
        }
        episodes.sort((a, b) -> Double.compare(b.depthPct(), a.depthPct()));
        return episodes.subList(0, Math.min(topN, episodes.size()));
    }

    /**
     * Pearson 相关矩阵（对齐日收益率，commons-math3）。
     * 注意 PearsonsCorrelation(double[][]) 要求行=观测（交易日）、列=变量（资产），
     * 且需剔除首个元素（对齐序列的 r[0] 为占位 0，非真实收益）。
     */
    public Map<String, Map<String, Double>> correlationMatrix() {
        String[] names = closes.keySet().toArray(new String[0]);
        int n = alignedDates.size();
        double[][] observations = new double[n - 1][names.length];   // 行=交易日观测
        for (int d = 0; d < n - 1; d++) {
            for (int a = 0; a < names.length; a++) {
                observations[d][a] = returns.get(names[a])[d + 1];   // 跳过 r[0] 占位
            }
        }
        PearsonsCorrelation pc = new PearsonsCorrelation(observations);
        Map<String, Map<String, Double>> out = new LinkedHashMap<>();
        for (int i = 0; i < names.length; i++) {
            Map<String, Double> row = new LinkedHashMap<>();
            for (int j = 0; j < names.length; j++) {
                row.put(names[j], pc.getCorrelationMatrix().getEntry(i, j));
            }
            out.put(names[i], row);
        }
        return out;
    }

    /**
     * 加权组合指标（对齐日收益率线性组合）。权重为 0 的资产不要求已加载。
     */
    public Metrics comboMetrics(Weighting w) {
        double[] gold = w.goldW() == 0 ? null : returns.get(assetName("GLD"));
        double[] btc = w.btcW() == 0 ? null : returns.get(assetName("BTC"));
        double[] spy = w.spyW() == 0 ? null : returns.get(assetName("SPY"));
        int n = alignedDates.size();
        double[] combo = new double[n];
        for (int i = 0; i < n; i++) {
            combo[i] = (gold == null ? 0 : w.goldW() * gold[i])
                    + (btc == null ? 0 : w.btcW() * btc[i])
                    + (spy == null ? 0 : w.spyW() * spy[i]);
        }
        // 组合净值序列（起点 1.0）
        double[] nav = new double[n];
        nav[0] = 1.0;
        for (int i = 1; i < n; i++) {
            nav[i] = nav[i - 1] * (1 + combo[i]);
        }
        double years = ChronoUnit.DAYS.between(alignedDates.getFirst(),
                alignedDates.getLast()) / 365.25;
        double cagr = Math.pow(nav[n - 1], 1 / Math.max(years, 1e-9)) - 1;
        DescriptiveStatistics ds = new DescriptiveStatistics();
        for (int i = 1; i < n; i++) {
            ds.addValue(combo[i]);
        }
        double periodsPerYear = n / Math.max(years, 1e-9);
        double annVol = ds.getStandardDeviation() * Math.sqrt(periodsPerYear);
        double peak = Double.NEGATIVE_INFINITY;
        double mdd = 0;
        for (double v : nav) {
            peak = Math.max(peak, v);
            mdd = Math.max(mdd, (peak - v) / peak);
        }
        return new Metrics(w.label(), cagr, annVol, mdd * 100,
                annVol == 0 ? 0 : cagr / annVol, n,
                alignedDates.getFirst(), alignedDates.getLast());
    }

    /**
     * 按子串找资产名（GLD/BTC/SPY 的完整键）。
     */
    private String assetName(String frag) {
        return closes.keySet().stream().filter(k -> k.contains(frag)).findFirst()
                .orElseThrow(() -> new IllegalStateException("缺少资产 " + frag));
    }
}
