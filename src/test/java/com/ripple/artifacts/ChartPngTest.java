package com.ripple.artifacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 图表渲染回归测试：R6 修复的"系列颜色依赖 HashMap 迭代序"缺陷在此固化——
 * 用 LinkedHashMap（确定迭代序：SPY 在前）构造数据，断言三条线都被绘制
 * 且各自使用自己的颜色（金/橙/蓝），与迭代顺序无关。
 */
class ChartPngTest {

    @TempDir
    Path tmp;

    @Test
    void allSeriesDrawnInOwnColorsRegardlessOfIterationOrder() throws Exception {
        int n = 100;
        Map<String, List<Double>> series = new LinkedHashMap<>();
        // SPY 故意放第一位（修复前若被先遍历会命中 palette[0]=金色）
        series.put("SPY（美股）", java.util.stream.IntStream.range(0, n)
                .mapToDouble(i -> 100 + i * 0.8).boxed().toList());
        series.put("GLD（黄金）", java.util.stream.IntStream.range(0, n)
                .mapToDouble(i -> 100 + i * 0.3).boxed().toList());
        series.put("BTC-USD（比特币）", java.util.stream.IntStream.range(0, n)
                .mapToDouble(i -> 100 + i * 0.1).boxed().toList());
        List<LocalDate> dates = java.util.stream.IntStream.range(0, n)
                .mapToObj(i -> LocalDate.of(2024, 1, 1).plusDays(i)).toList();

        Path png = tmp.resolve("chart.png");
        ChartPng.normalizedPrice(png, dates, series, "测试图");

        BufferedImage img = ImageIO.read(png.toFile());
        // 与 ChartPng 常量一致的三色
        int gold = count(img, 0xB8, 0x86, 0x0B);
        int btc = count(img, 0xE0, 0x7B, 0x00);
        int blue = count(img, 0x4A, 0x90, 0xD9);
        // 陡峭的蓝线（0.8/日）像素最多，平缓的橙线最少，但都必须显著多于图例色块（56px）
        assertTrue(gold > 200, "金色线缺失: " + gold);
        assertTrue(btc > 200, "橙色线缺失: " + btc);
        assertTrue(blue > 200, "蓝色线缺失（SPY 被画成金色的回归缺陷）: " + blue);
    }

    private static int count(BufferedImage img, int r, int g, int b) {
        int n = 0;
        for (int y = 0; y < img.getHeight(); y++) {
            for (int x = 0; x < img.getWidth(); x++) {
                int c = img.getRGB(x, y);
                if (Math.abs(((c >> 16) & 0xff) - r) <= 12
                        && Math.abs(((c >> 8) & 0xff) - g) <= 12
                        && Math.abs((c & 0xff) - b) <= 12) {
                    n++;
                }
            }
        }
        return n;
    }
}
