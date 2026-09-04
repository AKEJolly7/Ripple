package com.ripple.artifacts;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.geom.Path2D;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * 静态 PNG 折线图（Java2D 手绘，零第三方依赖）。
 * 【取舍说明】POI 的图表 API（XSSFChart/XSLFChart）能力弱：类型有限、样式难控、
 * WPS/Office 兼容性参差，且 XSLF 图表在老版本 POI 中支持很差；JFreeChart 功能强但引入
 * ~5MB 传递依赖。本项目只需 2 张归一化对比/回撤图，Java2D 120 行即可，故选之。
 */
public final class ChartPng {

    private static final Color GOLD = new Color(0xB8860B);
    private static final Color BTC = new Color(0xE07B00);
    private static final Color GRID = new Color(0xE3E8EE);
    private static final Color INK = new Color(0x1C2733);
    private static final Color MUTED = new Color(0x6B7A8C);

    /**
     * 归一化价格对比图（起点=100）。series: name → 数值序列（等长）。
     */
    public static void normalizedPrice(Path out, List<LocalDate> dates,
                                       Map<String, List<Double>> series, String title) throws IOException {
        draw(out, dates, series, title, null, false);
    }

    /**
     * 回撤图（%）。
     */
    public static void drawdown(Path out, List<LocalDate> dates,
                                Map<String, List<Double>> ddPctSeries, String title) throws IOException {
        draw(out, dates, ddPctSeries, title, null, true);
    }

    private static void draw(Path out, List<LocalDate> dates, Map<String, List<Double>> series,
                             String title, String yLabel, boolean zeroBaseTop) throws IOException {
        int w = 900;
        int h = 420;
        int left = 70;
        int right = 30;
        int top = 50;
        int bottom = 60;
        BufferedImage img = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g.setColor(Color.WHITE);
        g.fillRect(0, 0, w, h);

        double min = Double.POSITIVE_INFINITY;
        double max = Double.NEGATIVE_INFINITY;
        for (List<Double> s : series.values()) {
            for (double v : s) {
                if (!Double.isNaN(v)) {
                    min = Math.min(min, v);
                    max = Math.max(max, v);
                }
            }
        }
        if (zeroBaseTop) {
            min = Math.min(min, 0);
            max = Math.max(max, 0);
        }
        if (min == max) {
            max = min + 1;
        }
        double pad = (max - min) * 0.05;
        min -= pad;
        max += pad;

        // 标题与轴
        g.setColor(INK);
        g.setFont(new Font("PingFang SC", Font.BOLD, 16));
        g.drawString(title, left, 28);
        g.setFont(new Font("PingFang SC", Font.PLAIN, 11));
        g.setColor(MUTED);
        g.drawString(dates.isEmpty() ? "" : dates.getFirst() + " ~ " + dates.getLast(), left, h - 12);

        int plotW = w - left - right;
        int plotH = h - top - bottom;
        // 网格 + Y 轴刻度（5 段）
        for (int i = 0; i <= 5; i++) {
            double v = min + (max - min) * i / 5;
            int y = (int) (top + plotH - plotH * i / 5.0);
            g.setColor(GRID);
            g.drawLine(left, y, w - right, y);
            g.setColor(MUTED);
            g.drawString(String.format("%.0f", v), 8, y + 4);
        }

        // 折线（颜色只按 key 判定，与 series 迭代顺序无关——HashMap 迭代序不定，
        // 早期版本用 palette[ci++%3] 时 SPY 若先被遍历会被画成金色）
        for (var e : series.entrySet()) {
            List<Double> s = e.getValue();
            Color color = seriesColor(e.getKey());
            Path2D path = new Path2D.Double();
            boolean started = false;
            for (int i = 0; i < s.size(); i++) {
                double v = s.get(i);
                if (Double.isNaN(v)) {
                    continue;
                }
                double x = left + plotW * (double) i / Math.max(1, s.size() - 1);
                double y = top + plotH - plotH * (v - min) / (max - min);
                if (!started) {
                    path.moveTo(x, y);
                    started = true;
                } else {
                    path.lineTo(x, y);
                }
            }
            g.setColor(color);
            g.setStroke(new BasicStroke(2f));
            g.draw(path);
        }

        // 图例
        int lx = left;
        int ly = h - 30;
        for (var e : series.entrySet()) {
            Color color = seriesColor(e.getKey());
            g.setColor(color);
            g.fillRect(lx, ly - 8, 14, 4);
            g.setColor(INK);
            g.setFont(new Font("PingFang SC", Font.PLAIN, 12));
            g.drawString(e.getKey(), lx + 20, ly);
            lx += 40 + g.getFontMetrics().stringWidth(e.getKey());
        }

        g.dispose();
        ImageIO.write(img, "png", out.toFile());
    }

    /**
     * 系列颜色：金→金色、币→橙色、其余（SPY 等）→蓝色。仅按名称判定，迭代序无关。
     */
    private static Color seriesColor(String key) {
        if (key.contains("BTC")) {
            return BTC;
        }
        return key.contains("GLD") ? GOLD : new Color(0x4A90D9);
    }

    private ChartPng() {
    }
}
