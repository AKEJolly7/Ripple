package com.ripple.agent.tools;

import com.ripple.domain.InflectionPoint;
import com.ripple.domain.Ohlcv;
import com.ripple.domain.enums.PivotType;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 对齐拐点选择回归测试：R5 验收修复的选择策略在此固化——
 * 每季度显著性 Top-3 + 全局上限 48（时间全覆盖，GTC/B100 类中等显著性拐点不被高波动期挤占）
 * 与同日多类型去重。
 */
class RippleToolsTest {

    private static InflectionPoint pivot(LocalDate d, PivotType type, double pct, boolean spike) {
        return new InflectionPoint(d, type, pct, spike, d.minusDays(7), d.plusDays(7));
    }

    @Test
    void quarterlySelectionCoversEveryQuarterAndKeepsCap() {
        // 构造 4 年 8 个季度，每季 5 个拐点（显著性递增编号），总显著性排序会让
        // 2022 与 2024（高分季）霸占全局 Top——季度限量后每季必须都有代表
        List<InflectionPoint> pivots = new ArrayList<>();
        LocalDate start = LocalDate.of(2022, 1, 10);
        int sig = 1;
        for (int q = 0; q < 8; q++) {
            for (int k = 0; k < 5; k++) {
                LocalDate d = start.plusMonths(3L * q).plusDays(k);
                // 偶数季（2022/2023 前半）显著性 ×5，制造"高波动期挤占"
                double pct = (q % 2 == 0 ? 5.0 : 1.0) + sig % 5;
                pivots.add(pivot(d, PivotType.BIG_UP, pct, false));
                sig++;
            }
        }
        List<InflectionPoint> selected = RippleTools.selectAlignmentPivots(pivots);

        assertTrue(selected.size() <= 48, "全局上限 48");
        // 每季度恰好 3 个（8 季 × 3 = 24 < 48 上限）
        assertEquals(24, selected.size());
        var quarters = selected.stream().map(p ->
                p.date().getYear() + "Q" + ((p.date().getMonthValue() - 1) / 3 + 1)).distinct().toList();
        assertEquals(8, quarters.size(), "8 个季度全覆盖: " + quarters);
        // 结果按日期升序
        for (int i = 1; i < selected.size(); i++) {
            assertTrue(selected.get(i - 1).date().isBefore(selected.get(i).date()));
        }
    }

    @Test
    void sameDayMultipleTypesKeepOnlyOne() {
        // 同日 LOCAL_TOP + BIG_UP 只保留显著性排序在前的那个
        List<InflectionPoint> pivots = List.of(
                pivot(LocalDate.of(2025, 1, 27), PivotType.LOCAL_TOP, -16.97, true),
                pivot(LocalDate.of(2025, 1, 27), PivotType.BIG_DOWN, -16.97, true),
                pivot(LocalDate.of(2025, 4, 9), PivotType.BIG_UP, 18.7, false));
        List<InflectionPoint> selected = RippleTools.selectAlignmentPivots(pivots);
        assertEquals(2, selected.size());
        assertEquals(1, selected.stream().filter(p -> p.date().equals(LocalDate.of(2025, 1, 27))).count());
    }

    @Test
    void maCrossPivotsAreExcluded() {
        List<InflectionPoint> pivots = List.of(
                pivot(LocalDate.of(2024, 1, 15), PivotType.GOLDEN_CROSS, 0.5, false),
                pivot(LocalDate.of(2024, 1, 20), PivotType.BIG_UP, 9.0, false));
        List<InflectionPoint> selected = RippleTools.selectAlignmentPivots(pivots);
        assertEquals(1, selected.size());
        assertEquals(PivotType.BIG_UP, selected.get(0).type());
    }

    @Test
    void loadCandlesRoundTrip() throws Exception {
        // ohlcv 检查点的读写契约（含忽略未知字段的 @JsonIgnoreProperties 行为）
        var tmp = java.nio.file.Files.createTempDirectory("toolkit-test");
        var mapper = new com.fasterxml.jackson.databind.ObjectMapper()
                .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
        var candles = List.of(
                new Ohlcv(LocalDate.of(2024, 1, 2), 100, 102, 99, 101, 1_000));
        mapper.writeValue(tmp.resolve("X_ohlcv.json").toFile(),
                java.util.Map.of("symbol", "X", "source", "T",
                        "candles", candles, "windowStart", "2024-01-01", "windowEnd", "2024-12-31"));
        List<Ohlcv> loaded = com.ripple.AnalysisMain.loadCandles(tmp.resolve("X_ohlcv.json"));
        assertEquals(1, loaded.size());
        assertEquals(101, loaded.get(0).close(), 1e-9);
    }
}
