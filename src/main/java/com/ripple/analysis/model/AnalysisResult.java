package com.ripple.analysis.model;

import com.ripple.domain.InflectionPoint;
import com.ripple.domain.Ohlcv;
import com.ripple.domain.Segment;

import java.util.List;

/**
 * 一次完整分析的结果：K 线 + 指标序列 + 拐点 + 趋势段（供 R5 渲染复用）。
 */
public record AnalysisResult(
        List<Ohlcv> candles,
        List<InflectionPoint> pivots,
        List<Segment> segments,
        double[] ma5,
        double[] ma20,
        double[] ma60,
        double[] rsi14,
        double[] volatility20,
        boolean[] volumeSpike) {
}
