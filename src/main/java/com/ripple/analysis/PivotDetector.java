package com.ripple.analysis;

import com.ripple.analysis.model.AnalysisConfig;
import com.ripple.analysis.model.AnalysisResult;
import com.ripple.domain.InflectionPoint;
import com.ripple.domain.Ohlcv;
import com.ripple.domain.Segment;
import com.ripple.domain.enums.PivotType;
import com.ripple.domain.enums.TrendLabel;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 拐点与趋势段检测（确定性，无 LLM）。
 * <p>
 * 拐点四类信号：
 * ① 局部极值：滚动窗口（默认 ±5 交易日）内严格最大/最小的收盘价；
 * ② 均线交叉：MA20 上穿/下穿 MA60（金叉/死叉）；
 * ③ 显著单日涨跌：|日涨跌幅| ≥ 阈值（默认 5%）；
 * 每个拐点携带当日涨跌幅、异常放量标记与 ±5 交易日候选窗口。
 * <p>
 * 趋势段（zig-zag）：从极值到极值切分——顺方向创新高/新低时延续，
 * 回撤超过 retrace 阈值（默认 8%）时终结当前段；段标签按净涨跌幅
 * （斜率代理）判定：≥ +minTrend 为上涨，≤ -minTrend 为下跌，其余盘整。
 */
public final class PivotDetector {

    public AnalysisResult analyze(List<Ohlcv> candles, AnalysisConfig cfg) {
        int n = candles.size();
        double[] close = new double[n];
        long[] volume = new long[n];
        for (int i = 0; i < n; i++) {
            close[i] = candles.get(i).close();
            volume[i] = candles.get(i).volume();
        }

        double[] ma5 = Indicators.sma(close, cfg.ma5Period());
        double[] ma20 = Indicators.sma(close, cfg.ma20Period());
        double[] ma60 = Indicators.sma(close, cfg.ma60Period());
        double[] rsi14 = Indicators.rsi(close, cfg.rsiPeriod());
        double[] vol20 = Indicators.rollingVolatilityPct(close, cfg.volatilityWindow());
        boolean[] spikes = Indicators.volumeSpikes(volume, cfg.volumeWindow(), cfg.volumeSpikeMultiplier());

        List<InflectionPoint> pivots = new ArrayList<>();
        detectLocalExtrema(candles, close, spikes, cfg, pivots);
        detectMaCrosses(candles, close, spikes, ma20, ma60, cfg, pivots);
        detectBigMoves(candles, close, spikes, cfg, pivots);
        pivots.sort(Comparator.comparing(InflectionPoint::date)
                .thenComparing(p -> p.type().ordinal()));

        List<Segment> segments = segmentize(candles, close, cfg);
        return new AnalysisResult(candles, List.copyOf(pivots), segments,
                ma5, ma20, ma60, rsi14, vol20, spikes);
    }

    /**
     * ① 局部极值：窗口内严格最大/最小（严格性保证窗口内不会出现重复极值）。
     */
    private void detectLocalExtrema(List<Ohlcv> candles, double[] close, boolean[] spikes,
                                    AnalysisConfig cfg, List<InflectionPoint> out) {
        int w = cfg.localExtremeWindow();
        int n = close.length;
        for (int i = w; i < n - w; i++) {
            boolean top = true;
            boolean bottom = true;
            for (int j = i - w; j <= i + w; j++) {
                if (j == i) {
                    continue;
                }
                if (close[j] >= close[i]) {
                    top = false;
                }
                if (close[j] <= close[i]) {
                    bottom = false;
                }
            }
            if (top) {
                out.add(point(candles, close, spikes, i, PivotType.LOCAL_TOP));
            }
            if (bottom) {
                out.add(point(candles, close, spikes, i, PivotType.LOCAL_BOTTOM));
            }
        }
    }

    /**
     * ② MA20 × MA60 金叉/死叉。
     */
    private void detectMaCrosses(List<Ohlcv> candles, double[] close, boolean[] spikes,
                                 double[] fast, double[] slow, AnalysisConfig cfg,
                                 List<InflectionPoint> out) {
        for (int i = 1; i < close.length; i++) {
            if (Double.isNaN(fast[i - 1]) || Double.isNaN(slow[i - 1])
                    || Double.isNaN(fast[i]) || Double.isNaN(slow[i])) {
                continue;
            }
            boolean fastWasBelow = fast[i - 1] <= slow[i - 1];
            boolean fastWasAbove = fast[i - 1] >= slow[i - 1];
            if (fastWasBelow && fast[i] > slow[i]) {
                out.add(point(candles, close, spikes, i, PivotType.GOLDEN_CROSS));
            } else if (fastWasAbove && fast[i] < slow[i]) {
                out.add(point(candles, close, spikes, i, PivotType.DEATH_CROSS));
            }
        }
    }

    /**
     * ③ 显著单日涨跌。
     */
    private void detectBigMoves(List<Ohlcv> candles, double[] close, boolean[] spikes,
                                AnalysisConfig cfg, List<InflectionPoint> out) {
        for (int i = 1; i < close.length; i++) {
            double pct = (close[i] / close[i - 1] - 1) * 100;
            if (pct >= cfg.bigMovePct()) {
                out.add(point(candles, close, spikes, i, PivotType.BIG_UP));
            } else if (pct <= -cfg.bigMovePct()) {
                out.add(point(candles, close, spikes, i, PivotType.BIG_DOWN));
            }
        }
    }

    private InflectionPoint point(List<Ohlcv> candles, double[] close, boolean[] spikes,
                                  int i, PivotType type) {
        int n = candles.size();
        double dayChange = i > 0 ? (close[i] / close[i - 1] - 1) * 100 : 0;
        return new InflectionPoint(
                candles.get(i).date(), type, dayChange, spikes[i],
                candles.get(Math.max(0, i - 5)).date(),
                candles.get(Math.min(n - 1, i + 5)).date());
    }

    /**
     * 趋势段划分（zig-zag，回撤阈值终止 + 净涨跌幅标签）。
     */
    private List<Segment> segmentize(List<Ohlcv> candles, double[] close, AnalysisConfig cfg) {
        int n = close.length;
        List<Segment> out = new ArrayList<>();
        if (n == 0) {
            return out;
        }
        double retrace = cfg.segmentRetracePct() / 100;
        int segStart = 0;       // 段起点（一个极值）
        int dir = 0;            // 0 未定，1 上，-1 下
        int extremeIdx = 0;     // 当前段顺方向极值位置
        double extreme = close[0];
        // 方向未定时同时跟踪窗口内高低点
        int hiIdx = 0, loIdx = 0;
        double hi = close[0], lo = close[0];

        for (int i = 1; i < n; i++) {
            double c = close[i];
            if (dir == 0) {
                if (c > hi) {
                    hi = c;
                    hiIdx = i;
                }
                if (c < lo) {
                    lo = c;
                    loIdx = i;
                }
                if (hi / lo - 1 >= retrace) {
                    if (hiIdx > loIdx) {          // 先低后高 → 向上段，起点取低点
                        segStart = loIdx;
                        dir = 1;
                        extreme = hi;
                        extremeIdx = hiIdx;
                    } else {                       // 先高后低 → 向下段
                        segStart = hiIdx;
                        dir = -1;
                        extreme = lo;
                        extremeIdx = loIdx;
                    }
                }
                continue;
            }
            if (dir == 1) {
                if (c > extreme) {                 // 顺方向新高，延续
                    extreme = c;
                    extremeIdx = i;
                } else if (c < extreme * (1 - retrace)) {  // 回撤超阈值，终结段
                    out.add(buildSegment(candles, close, segStart, extremeIdx, cfg));
                    segStart = extremeIdx;         // 新段自旧极值起
                    dir = -1;
                    extreme = c;
                    extremeIdx = i;
                }
            } else {
                if (c < extreme) {
                    extreme = c;
                    extremeIdx = i;
                } else if (c > extreme * (1 + retrace)) {
                    out.add(buildSegment(candles, close, segStart, extremeIdx, cfg));
                    segStart = extremeIdx;
                    dir = 1;
                    extreme = c;
                    extremeIdx = i;
                }
            }
        }
        // 收尾段（从未终结到序列末尾）
        int endIdx = dir == 0 ? n - 1 : extremeIdx;
        if (endIdx > segStart || out.isEmpty()) {
            out.add(buildSegment(candles, close, Math.min(segStart, endIdx), Math.max(segStart, endIdx), cfg));
        }
        return out;
    }

    private Segment buildSegment(List<Ohlcv> candles, double[] close, int start, int end,
                                 AnalysisConfig cfg) {
        double startPrice = close[start];
        double endPrice = close[end];
        double netPct = (endPrice - startPrice) / startPrice * 100;
        TrendLabel label = netPct >= cfg.segmentMinTrendPct() ? TrendLabel.UP
                : netPct <= -cfg.segmentMinTrendPct() ? TrendLabel.DOWN
                  : TrendLabel.SIDEWAYS;
        // 段内最大回撤（峰到其后最低点）
        double peak = close[start];
        double mdd = 0;
        for (int i = start; i <= end; i++) {
            peak = Math.max(peak, close[i]);
            mdd = Math.max(mdd, (peak - close[i]) / peak * 100);
        }
        return new Segment(candles.get(start).date(), candles.get(end).date(),
                label, startPrice, endPrice, mdd);
    }
}
