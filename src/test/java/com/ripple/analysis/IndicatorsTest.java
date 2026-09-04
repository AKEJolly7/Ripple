package com.ripple.analysis;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 指标纯函数的数学正确性（手算期望值，无网络）。
 */
class IndicatorsTest {

    @Test
    void smaSlidingWindowAndNanPrefix() {
        double[] out = Indicators.sma(new double[]{1, 2, 3, 4, 5}, 3);
        assertTrue(Double.isNaN(out[0]));
        assertTrue(Double.isNaN(out[1]));
        assertEquals(2.0, out[2], 1e-9);
        assertEquals(3.0, out[3], 1e-9);
        assertEquals(4.0, out[4], 1e-9);
    }

    @Test
    void rsiIs100ForPureGainAnd0ForPureLoss() {
        double[] up = {100, 101, 102, 103, 104, 105, 106};
        double[] down = {100, 99, 98, 97, 96, 95, 94};
        assertEquals(100.0, Indicators.rsi(up, 3)[6], 1e-9);
        assertEquals(0.0, Indicators.rsi(down, 3)[6], 1e-9);
        // Wilder 平滑对涨跌相位敏感，交替序列手算精确值：
        // {+1,-1,+1,-1} p=2 → avgG=0.375, avgL=0.625, RS=0.6 → RSI=37.5
        double[] alt = {100, 101, 100, 101, 100};
        assertEquals(37.5, Indicators.rsi(alt, 2)[4], 1e-9);
    }

    @Test
    void rollingVolatilityZeroForConstantSeries() {
        double[] flat = {100, 100, 100, 100, 100, 100};
        double[] out = Indicators.rollingVolatilityPct(flat, 3);
        assertEquals(0.0, out[5], 1e-12);
        assertTrue(Double.isNaN(out[2]));
    }

    @Test
    void volumeSpikeUsesTrailingMeanExcludingCurrentDay() {
        long[] vol = {10, 10, 10, 10, 40, 10};
        boolean[] out = Indicators.volumeSpikes(vol, 4, 3.0);
        assertFalse(out[3]);                       // 前 window 天不判定
        assertTrue(out[4]);                        // 40 > 3 × 前四日均值 10
        assertFalse(out[5]);                       // 10 未超
    }
}
