package com.ripple.analysis.model;

import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

/**
 * 技术分析阈值配置：全部从 classpath 的 analysis.properties 读取，
 * 支持命令行 -D 同名键覆盖（如 -Dsegment.retrace.pct=10）实现参数化。
 */
public record AnalysisConfig(
        int localExtremeWindow,
        int ma5Period, int ma20Period, int ma60Period,
        int maCrossShort, int maCrossLong,
        int rsiPeriod,
        int volatilityWindow,
        int volumeWindow,
        double volumeSpikeMultiplier,
        double bigMovePct,
        double segmentRetracePct,
        double segmentMinTrendPct) {

    public static AnalysisConfig load() {
        Properties props = new Properties();
        try (InputStream in = AnalysisConfig.class.getResourceAsStream("/analysis.properties")) {
            if (in != null) {
                props.load(in);
            }
        } catch (IOException e) {
            throw new IllegalStateException("读取 analysis.properties 失败", e);
        }
        // -D 同名覆盖优先，便于不改文件调参
        for (Object key : props.keySet()) {
            String k = String.valueOf(key);
            String override = System.getProperty(k);
            if (override != null) {
                props.setProperty(k, override);
            }
        }
        return new AnalysisConfig(
                intOf(props, "local.extreme.window", 5),
                intOf(props, "ma.5", 5),
                intOf(props, "ma.20", 20),
                intOf(props, "ma.60", 60),
                intOf(props, "ma.cross.short", 20),
                intOf(props, "ma.cross.long", 60),
                intOf(props, "rsi.period", 14),
                intOf(props, "volatility.window", 20),
                intOf(props, "volume.window", 20),
                doubleOf(props, "volume.spike.multiplier", 3.0),
                doubleOf(props, "big.move.pct", 5.0),
                doubleOf(props, "segment.retrace.pct", 8.0),
                doubleOf(props, "segment.min.trend.pct", 5.0));
    }

    private static int intOf(Properties p, String key, int fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Integer.parseInt(v.trim());
    }

    private static double doubleOf(Properties p, String key, double fallback) {
        String v = p.getProperty(key);
        return v == null ? fallback : Double.parseDouble(v.trim());
    }
}
