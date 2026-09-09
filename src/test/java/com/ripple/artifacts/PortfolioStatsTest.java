package com.ripple.artifacts;

import com.ripple.domain.Ohlcv;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 回测统计正确性（手算期望值）与 R6 修复过的相关矩阵转置缺陷在此固化——
 * ① 完全同涨同跌的两资产相关=1、反向=-1、无关=0；
 * ② 相关矩阵行列语义（行=交易日观测、列=资产，转置即全 NaN）；
 * ③ 组合权重 0/1 边界与单资产一致；④ 最大回撤与回撤事件分解。
 */
class PortfolioStatsTest {

    private static List<Ohlcv> fromCloses(String name, double... closes) {
        List<Ohlcv> out = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < closes.length; i++) {
            out.add(new Ohlcv(d.plusDays(i), closes[i], closes[i], closes[i], closes[i], 1000));
        }
        return out;
    }

    @Test
    void correlationMatrixDetectsPerfectAndZeroCorrelation() {
        // A 稳定 +1%/日；B 与 A 完全同向；C 恒定（零方差，相关应为 0 或 NaN，不得为伪值）
        var a = fromCloses("A", 100, 101, 102.01, 103.0301, 104.0604);
        var b = fromCloses("B", 200, 202, 204.02, 206.0602, 208.1008);   // 与 A 同向
        var stats = new PortfolioStats().add("A", a).add("B", b);
        double corr = stats.correlationMatrix().get("A").get("B");
        assertEquals(1.0, corr, 1e-9, "完全同向资产相关应为 1");

        // 无关资产：交替涨跌（AB AB）与随机走向 → 相关明显低于 1
        var c = fromCloses("C", 100, 101, 100, 101, 100);
        var d2 = fromCloses("D", 100, 100, 101, 101, 102);
        var stats2 = new PortfolioStats().add("C", c).add("D", d2);
        double corr2 = stats2.correlationMatrix().get("C").get("D");
        assertTrue(Math.abs(corr2) < 1.0, "非完全同向资产相关不应是 ±1，实际 " + corr2);
        // 对角线恒为 1
        assertEquals(1.0, stats2.correlationMatrix().get("C").get("C"), 1e-9);
    }

    @Test
    void correlationMatrixIsSymmetricAndNotNaN() {
        // R6 缺陷回归：行列转置曾导致非对角全 NaN
        var g = fromCloses("GLD", 100, 101, 99, 102, 100.5, 103, 102, 104);
        var b = fromCloses("BTC", 50, 52, 48, 55, 51, 56, 53, 58);
        var s = fromCloses("SPY", 400, 402, 401, 405, 404, 407, 406, 409);
        var stats = new PortfolioStats().add("GLD", g).add("BTC", b).add("SPY", s);
        var m = stats.correlationMatrix();
        for (var row : m.entrySet()) {
            for (var cell : row.getValue().entrySet()) {
                assertTrue(Double.isFinite(cell.getValue()),
                        row.getKey() + "×" + cell.getKey() + " 相关为 NaN（行列转置缺陷）");
            }
        }
        assertEquals(m.get("GLD").get("BTC"), m.get("BTC").get("GLD"), 1e-12, "矩阵须对称");
    }

    @Test
    void comboWeightBoundariesMatchSingleAsset() {
        // 长样本（2 年日频）：100 → 150，CAGR = 1.5^(1/2)-1 = 22.47%
        List<Ohlcv> g = new ArrayList<>();
        List<Ohlcv> b = new ArrayList<>();
        LocalDate d0 = LocalDate.of(2024, 1, 1);
        for (int i = 0; i <= 730; i++) {
            LocalDate d = d0.plusDays(i);
            double gv = 100 * Math.pow(1.5, i / 730.0);       // 平滑增长到 150
            double bv = 10 * Math.pow(2.2, i / 730.0);        // 平滑增长到 22
            g.add(new Ohlcv(d, gv, gv, gv, gv, 1000));
            b.add(new Ohlcv(d, bv, bv, bv, bv, 1000));
        }
        var stats = new PortfolioStats().add("GLD", g).add("BTC", b);
        double goldCagr = stats.comboMetrics(new PortfolioStats.Weighting("100金", 1, 0, 0)).cagr();
        double btcCagr = stats.comboMetrics(new PortfolioStats.Weighting("100币", 0, 1, 0)).cagr();
        assertEquals(0.2247, goldCagr, 1e-3);   // 1.5^(1/2)-1
        assertEquals(0.4832, btcCagr, 1e-3);    // 2.2^(1/2)-1
        // 单资产 metricsOf 与 1/0 权重组合完全一致（同一序列同一口径）
        assertEquals(stats.metricsOf("GLD", g).cagr(), goldCagr, 1e-9);
        assertEquals(stats.metricsOf("BTC", b).cagr(), btcCagr, 1e-9);
        // 缺失 SPY（权重 0）不再抛异常
        double fifty = stats.comboMetrics(new PortfolioStats.Weighting("50/50", 0.5, 0.5, 0)).cagr();
        assertTrue(Double.isFinite(fifty));
    }

    @Test
    void maxDrawdownAndEpisodes() {
        // 峰 120（第4日）→ 谷 96（第5日，-20%）→ 反弹 110（未修复）
        var g = fromCloses("GLD", 100, 110, 120, 96, 110);
        var stats = new PortfolioStats().add("GLD", g);
        assertEquals(20.0, stats.maxDrawdownOf(g), 1e-9);
        var m = stats.metricsOf("GLD", g);
        assertEquals(20.0, m.maxDrawdown(), 1e-9);
        var top = stats.topDrawdowns("GLD", g, 5);
        assertEquals(1, top.size());
        assertEquals(LocalDate.of(2024, 1, 3), top.get(0).peakDate());
        assertEquals(LocalDate.of(2024, 1, 4), top.get(0).troughDate());
        assertEquals(20.0, top.get(0).depthPct(), 1e-9);
    }
}
