package com.ripple.analysis;

import com.ripple.analysis.model.AnalysisConfig;
import com.ripple.analysis.model.AnalysisResult;

import com.ripple.domain.Ohlcv;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 真实 NVDA 数据锚点断言：AI 行业三大事件（ChatGPT / GTC B100 / DeepSeek）
 * 附近 ±14 个日历日（≈10 个交易日）必须检出拐点。
 * 依赖 work/NVDA_ohlcv.json（DataFetchMain 产物，已 gitignore），
 * 文件不存在时跳过——本地/CI 先跑 DataFetchMain 再跑本测试。
 */
class RealNvdaPivotTest {

    private static final List<LocalDate> EVENT_ANCHORS = List.of(
            LocalDate.of(2022, 11, 30),   // ChatGPT 发布
            LocalDate.of(2024, 3, 8),     // GTC 2024，B100 发布
            LocalDate.of(2025, 1, 27));   // DeepSeek-R1 冲击日

    @Test
    void detectsSignificantPivotsNearAiIndustryEvents() throws Exception {
        Path file = Path.of("work/NVDA_ohlcv.json");
        Assumptions.assumeTrue(Files.exists(file), "缺少 work/NVDA_ohlcv.json，跳过真实数据断言");

        List<Ohlcv> candles = com.ripple.AnalysisMain.loadCandles(file);
        AnalysisResult result = new PivotDetector().analyze(candles, AnalysisConfig.load());

        for (LocalDate anchor : EVENT_ANCHORS) {
            boolean hit = result.pivots().stream().anyMatch(p ->
                    Math.abs(ChronoUnit.DAYS.between(anchor, p.date())) <= 14);
            assertTrue(hit, () -> "%s 附近 ±14 天未检出任何拐点，需调整阈值（当前 retrace=8%%，"
                    .formatted(anchor) + "extremeWindow=5，bigMove=5%）");
        }
    }
}
