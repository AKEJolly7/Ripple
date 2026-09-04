package com.ripple.analysis;

/**
 * 确定性技术指标计算（纯函数，无状态，可单测）。NaN 表示窗口不足的前缀。
 */
public final class Indicators {

    private Indicators() {
    }

    /**
     * 简单移动平均。
     */
    public static double[] sma(double[] values, int period) {
        double[] out = new double[values.length];
        double sum = 0;
        for (int i = 0; i < values.length; i++) {
            sum += values[i];
            if (i >= period) {
                sum -= values[i - period];
            }
            out[i] = i >= period - 1 ? sum / period : Double.NaN;
        }
        return out;
    }

    /**
     * Wilder 平滑 RSI（0-100）。全为上涨返回 100，全为下跌返回 0。
     */
    public static double[] rsi(double[] close, int period) {
        int n = close.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        if (n <= period) {
            return out;
        }
        double avgGain = 0, avgLoss = 0;
        for (int i = 1; i <= period; i++) {
            double d = close[i] - close[i - 1];
            avgGain += Math.max(d, 0);
            avgLoss += Math.max(-d, 0);
        }
        avgGain /= period;
        avgLoss /= period;
        out[period] = rsiValue(avgGain, avgLoss);
        for (int i = period + 1; i < n; i++) {
            double d = close[i] - close[i - 1];
            avgGain = (avgGain * (period - 1) + Math.max(d, 0)) / period;
            avgLoss = (avgLoss * (period - 1) + Math.max(-d, 0)) / period;
            out[i] = rsiValue(avgGain, avgLoss);
        }
        return out;
    }

    private static double rsiValue(double avgGain, double avgLoss) {
        if (avgLoss == 0) {
            return avgGain == 0 ? 50 : 100;
        }
        double rs = avgGain / avgLoss;
        return 100 - 100 / (1 + rs);
    }

    /**
     * 滚动波动率：最近 window 个日收益率的样本标准差（%）。
     */
    public static double[] rollingVolatilityPct(double[] close, int window) {
        int n = close.length;
        double[] out = new double[n];
        java.util.Arrays.fill(out, Double.NaN);
        // ret[i] 为第 i 日相对前一日收益（i 从 1 起）；vol[i] 覆盖 ret[i-window+1..i]
        for (int i = window; i < n; i++) {
            double sum = 0, sumSq = 0;
            for (int j = i - window + 1; j <= i; j++) {
                double r = close[j] / close[j - 1] - 1;
                sum += r;
                sumSq += r * r;
            }
            double mean = sum / window;
            double variance = Math.max(0, sumSq / window - mean * mean);
            out[i] = Math.sqrt(variance) * 100;
        }
        return out;
    }

    /**
     * 异常放量标记：当日成交量 > multiplier × 前 window 日均量。
     * 均值不含当日，避免大放量日拉高自己的基准。
     */
    public static boolean[] volumeSpikes(long[] volume, int window, double multiplier) {
        int n = volume.length;
        boolean[] out = new boolean[n];
        for (int i = window; i < n; i++) {
            double mean = 0;
            for (int j = i - window; j < i; j++) {
                mean += volume[j];
            }
            mean /= window;
            out[i] = volume[i] > multiplier * mean;
        }
        return out;
    }
}
