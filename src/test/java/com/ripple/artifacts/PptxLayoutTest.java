package com.ripple.artifacts;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * PPT 版式回归：XSLFTable 默认每列 100pt 且不受 anchor 宽约束——组合页表格 5 列
 * 曾实际渲染 500pt（anchor 仅 410），溢出压住右侧图表盖住最右列字符（用户实开发现）。
 * 修复：table() 显式按份额分配列宽。此处固化两个不变式：
 * ① 表格列宽合计 ≤ anchor 声明宽度；② 图片左边界 ≥ 表格实际右边界。
 */
class PptxLayoutTest {

    @Test
    void comboSlideTableDoesNotOverflowIntoChart(@TempDir Path dir) throws Exception {
        ExcelWriter.Snapshot snap = TestSnapshots.minimal();
        Path png = dir.resolve("chart.png");
        Map<String, List<Double>> norm = new HashMap<>();
        for (var e : snap.closes().entrySet()) {
            double base = e.getValue().get(0);
            norm.put(e.getKey(), e.getValue().stream().map(v -> v / base * 100).toList());
        }
        ChartPng.normalizedPrice(png, snap.dates(), norm, "test");
        Path out = dir.resolve("t.pptx");
        new PptxWriter().write(snap, png, out);

        String xml = slideXml(out, "组合情景与决策建议");
        double colSum = gridColWidthsPt(xml).stream().mapToDouble(Double::doubleValue).sum();
        assertTrue(colSum <= 411, "表格列宽合计 " + colSum + "pt 超出 anchor 声明的 410pt");

        double tableRight = 60 + colSum;
        double picLeft = pictureLeftPt(xml);
        assertTrue(picLeft >= tableRight,
                "图片左边界 " + picLeft + "pt 未避开表格实际右边界 " + tableRight + "pt");
    }

    /**
     * 从 pptx zip 中取标题含关键词的 slide XML。
     */
    private static String slideXml(Path pptx, String titleKeyword) throws IOException {
        try (ZipFile zip = new ZipFile(pptx.toFile())) {
            for (Enumeration<? extends ZipEntry> e = zip.entries(); e.hasMoreElements(); ) {
                ZipEntry entry = e.nextElement();
                if (!entry.getName().matches("ppt/slides/slide\\d+\\.xml")) {
                    continue;
                }
                String xml = new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8);
                if (xml.contains(titleKeyword)) {
                    return xml;
                }
            }
        }
        throw new AssertionError("未找到标题含 " + titleKeyword + " 的 slide");
    }

    private static List<Double> gridColWidthsPt(String xml) {
        Matcher m = Pattern.compile("<a:gridCol w=\"(\\d+)\"").matcher(xml);
        List<Double> pts = new ArrayList<>();
        while (m.find()) {
            pts.add(Long.parseLong(m.group(1)) / 12700.0);
        }
        return pts;
    }

    private static double pictureLeftPt(String xml) {
        Matcher m = Pattern.compile("<p:pic>.*?<a:off x=\"(\\d+)\"", Pattern.DOTALL).matcher(xml);
        if (!m.find()) {
            throw new AssertionError("slide 中未找到图片");
        }
        return Long.parseLong(m.group(1)) / 12700.0;
    }
}
