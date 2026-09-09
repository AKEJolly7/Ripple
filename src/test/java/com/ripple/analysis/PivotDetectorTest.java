package com.ripple.analysis;

import com.ripple.analysis.model.AnalysisConfig;
import com.ripple.analysis.model.AnalysisResult;

import com.ripple.domain.InflectionPoint;
import com.ripple.domain.Ohlcv;
import com.ripple.domain.enums.PivotType;
import com.ripple.domain.Segment;
import com.ripple.domain.enums.TrendLabel;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 合成锯齿序列验证四类拐点信号与趋势段划分（无网络）。
 */
class PivotDetectorTest {

    // 上涨 100→110（10 天）→ 单日崩至 95（-13.6%）→ 阴跌至 85 → 单日跳至 100（+17.6%）→ 缓涨至 105
    private static final double[] CLOSES = {
            100, 101, 102, 103, 104, 105, 106, 107, 108, 109, 110,   // idx 0-10
            95, 94, 93, 92, 91, 90, 89, 88, 87, 86, 85,               // idx 11-21
            100, 101, 102, 103, 104, 105};                            // idx 22-27

    private static List<Ohlcv> candles() {
        java.util.ArrayList<Ohlcv> list = new java.util.ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < CLOSES.length; i++) {
            long volume = i == 11 ? 10_000 : 1_000;   // 崩盘日异常放量 10 倍
            list.add(new Ohlcv(d.plusDays(i), CLOSES[i], CLOSES[i], CLOSES[i], CLOSES[i], volume));
        }
        return list;
    }

    private static AnalysisConfig cfg() {
        return new AnalysisConfig(3, 5, 5, 10, 5, 10, 3, 3, 5, 3.0, 5.0, 8.0, 5.0);
    }

    private final AnalysisResult result = new PivotDetector().analyze(candles(), cfg());

    @Test
    void detectsLocalTopAndBottomAtExtremes() {
        List<InflectionPoint> tops = byType(PivotType.LOCAL_TOP);
        List<InflectionPoint> bottoms = byType(PivotType.LOCAL_BOTTOM);
        assertEquals(1, tops.size());
        assertEquals(LocalDate.of(2024, 1, 11), tops.get(0).date());   // 110 峰
        assertEquals(1, bottoms.size());
        assertEquals(LocalDate.of(2024, 1, 22), bottoms.get(0).date()); // 85 谷
    }

    @Test
    void detectsBigMovesWithVolumeSpikeAndCandidateWindow() {
        List<InflectionPoint> downs = byType(PivotType.BIG_DOWN);
        List<InflectionPoint> ups = byType(PivotType.BIG_UP);
        assertEquals(1, downs.size());
        assertEquals(1, ups.size());

        InflectionPoint crash = downs.get(0);
        assertEquals(LocalDate.of(2024, 1, 12), crash.date());
        assertEquals(-13.64, crash.dayChangePct(), 0.01);
        assertTrue(crash.volumeSpike());                                   // 崩盘日放量
        // 候选窗口 = 前后 ±5 个交易日（合成序列无休市，即日历日 ±5）
        assertEquals(LocalDate.of(2024, 1, 7), crash.windowStart());
        assertEquals(LocalDate.of(2024, 1, 17), crash.windowEnd());
    }

    @Test
    void detectsMaCrosses() {
        // 崩盘后 MA5 下穿 MA10 → 死叉；反弹后 MA5 上穿 MA10 → 金叉
        assertFalse(byType(PivotType.DEATH_CROSS).isEmpty());
        assertFalse(byType(PivotType.GOLDEN_CROSS).isEmpty());
    }

    @Test
    void segmentsFollowZigZagWithTrendLabels() {
        List<Segment> segs = result.segments();
        assertEquals(3, segs.size());

        Segment up = segs.get(0);
        assertEquals(TrendLabel.UP, up.label());
        assertEquals(100.0, up.startPrice(), 1e-9);
        assertEquals(110.0, up.endPrice(), 1e-9);

        Segment down = segs.get(1);
        assertEquals(TrendLabel.DOWN, down.label());
        assertEquals(110.0, down.startPrice(), 1e-9);
        assertEquals(85.0, down.endPrice(), 1e-9);
        assertTrue(down.maxDrawdownPct() > 20);   // 110→85 全程回撤

        Segment up2 = segs.get(2);
        assertEquals(TrendLabel.UP, up2.label());
        assertEquals(85.0, up2.startPrice(), 1e-9);
    }

    @Test
    void segmentsAreContiguousInTime() {
        List<Segment> segs = result.segments();
        for (int i = 1; i < segs.size(); i++) {
            assertFalse(segs.get(i).startDate().isBefore(segs.get(i - 1).endDate()),
                    "段 %d 与前段不连续".formatted(i));
        }
    }

    private List<InflectionPoint> byType(PivotType type) {
        return result.pivots().stream().filter(p -> p.type() == type).toList();
    }
}
