package com.ripple.artifacts;

import org.apache.poi.xslf.usermodel.*;

import java.awt.*;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * PPT 决策框架（POI XSLF）：5 页——问题定义 / 指标对比 / 风险与相关性 / 组合情景 / 决策建议。
 * 【取舍说明】POI 的原生图表（XSLFChart）能力弱：类型少、样式控制有限、跨 Office/WPS 渲染
 * 不一致，故图表采用静态 PNG（ChartPng 生成）嵌入；表格为 XSLFTable 原生（可编辑）。
 */
public final class PptxWriter {

    private static final Color INK = new Color(0x1C2733);
    private static final Color ACCENT = new Color(0xB8860B);
    private static final Color MUTED = new Color(0x6B7A8C);

    public PptxWriter() {
    }

    public Path write(ExcelWriter.Snapshot snap, Path chartPng, Path outFile) throws IOException {
        try (XMLSlideShow ppt = new XMLSlideShow()) {
            ppt.setPageSize(new java.awt.Dimension(960, 540));

            slideProblem(ppt);
            slideMetrics(ppt, snap);
            slideRisk(ppt, snap);
            slideCombos(ppt, snap, chartPng);
            slideAdvice(ppt, snap);

            Path parent = outFile.toAbsolutePath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            try (FileOutputStream out = new FileOutputStream(outFile.toFile())) {
                ppt.write(out);
            }
        }
        return outFile;
    }

    private void slideProblem(XMLSlideShow ppt) {
        XSLFSlide s = ppt.createSlide();
        title(s, "黄金 vs 比特币：避险/抗通胀资产决策框架");
        bullets(s, List.of(
                "问题：在避险与抗通胀配置中，黄金（GLD）与比特币（BTC）各自扮演什么角色？",
                "样本：2021-09 ~ 2026-08 近五年日线（GLD / BTC-USD / SPY 三资产日期交集对齐）",
                "方法：确定性回测（CAGR、年化波动、最大回撤、夏普、Pearson 相关、权重组合情景）",
                "口径：CAGR 按日历时间；夏普 rf=0；回撤为收盘价口径；BTC 为 7×24 交易日历（约 365 天/年）",
                "工具：Apache POI XSLF 表格 + Java2D 静态 PNG 图（POI 原生图表能力弱，取舍见代码注释）"));
    }

    private void slideMetrics(XMLSlideShow ppt, ExcelWriter.Snapshot snap) {
        XSLFSlide s = ppt.createSlide();
        title(s, "指标对比：收益、风险与效率");
        List<String[]> rows = snap.assetOrder().stream()
                .map(a -> {
                    var m = snap.metrics().get(a);
                    return new String[]{a, pct(m.cagr()), pct(m.annVol()),
                            pct(m.maxDrawdown() / 100), num(m.sharpe()), String.valueOf(m.days())};
                })
                .toList();
        table(s, 90, new String[]{"资产", "年化收益", "年化波动", "最大回撤", "夏普(rf=0)", "交易日数"},
                rows);
        footnote(s, "夏普 rf=0 为简化口径；GLD 为黄金 ETF（价格代理），BTC 为 Binance BTCUSDT。");
    }

    private void slideRisk(XMLSlideShow ppt, ExcelWriter.Snapshot snap) {
        XSLFSlide s = ppt.createSlide();
        title(s, "风险与相关性");
        List<String> names = snap.assetOrder();
        List<String[]> rows = names.stream()
                .map(a -> {
                    Map<String, Double> row = snap.correlations().get(a);
                    return new String[]{a, num(row.get(names.get(0))), num(row.get(names.get(1))),
                            num(row.get(names.get(2)))};
                })
                .toList();
        table(s, 90, new String[]{"日收益率 Pearson 相关", names.get(0), names.get(1), names.get(2)}, rows);
        // 最大回撤事件摘要
        StringBuilder sb = new StringBuilder();
        for (String a : snap.assetOrder()) {
            var top = snap.drawdowns().get(a).getFirst();
            sb.append(a).append("最大回撤 ").append(pct(top.depthPct() / 100))
                    .append("（").append(top.peakDate()).append(" → ").append(top.troughDate())
                    .append("，").append(top.peakToTroughDays()).append(" 天）  ");
        }
        footnote(s, sb.toString());
    }

    private void slideCombos(XMLSlideShow ppt, ExcelWriter.Snapshot snap, Path chartPng)
            throws IOException {
        XSLFSlide s = ppt.createSlide();
        title(s, "组合情景与决策建议");
        List<String[]> rows = snap.combos().stream()
                .map(m -> new String[]{m.name(), pct(m.cagr()), pct(m.annVol()),
                        pct(m.maxDrawdown() / 100), num(m.sharpe())})
                .toList();
        // 表格收窄到左半区（x 60~470），图片放右半区（x 500~930），避免重叠
        table(s, 80, 410, new String[]{"组合（金/币/股）", "年化收益", "年化波动", "最大回撤", "夏普"}, rows);
        if (chartPng != null && java.nio.file.Files.exists(chartPng)) {
            byte[] png = java.nio.file.Files.readAllBytes(chartPng);
            var picData = ppt.addPicture(png,
                    org.apache.poi.sl.usermodel.PictureData.PictureType.PNG);
            s.createPicture(picData).setAnchor(new Rectangle(500, 130, 420, 196));
        }
        footnote(s, "组合基于三资产日期交集的日收益率线性加权，逐日再平衡假设。");
    }

    private void slideAdvice(XMLSlideShow ppt, ExcelWriter.Snapshot snap) {
        XSLFSlide s = ppt.createSlide();
        title(s, "决策建议");
        var gld = snap.metrics().get(nameOf(snap, "GLD"));
        var btc = snap.metrics().get(nameOf(snap, "BTC"));
        double corr = snap.correlations().get(nameOf(snap, "GLD")).get(nameOf(snap, "BTC"));
        bullets(s, List.of(
                "黄金：低波动（" + pct(gld.annVol()) + "）、浅回撤（" + pct(gld.maxDrawdown() / 100)
                        + "）——避险压舱石，收益弹性有限（CAGR " + pct(gld.cagr()) + "）",
                "比特币：高波动（" + pct(btc.annVol()) + "）、深回撤（" + pct(btc.maxDrawdown() / 100)
                        + "）——抗通胀/高风险偏好增强器，CAGR " + pct(btc.cagr()),
                "两者日收益率相关仅 " + num(corr) + "，同仓配置有分散化价值",
                "建议框架：核心避险仓位以黄金为主（如 60/40 中金的权重），比特币作为卫星仓位严格控制回撤预算",
                "执行：单一组合权重非最优解，按风险预算（波动贡献）与回撤容忍度再平衡；详见 Word 策略报告"));
    }

    // ------------------------------------------------------------------
    // 版式工具
    // ------------------------------------------------------------------

    private static String nameOf(ExcelWriter.Snapshot snap, String frag) {
        return snap.assetOrder().stream().filter(a -> a.contains(frag)).findFirst().orElse(frag);
    }

    private void title(XSLFSlide s, String text) {
        XSLFTextShape t = s.createTextBox();
        t.setAnchor(new Rectangle(50, 28, 860, 48));
        XSLFTextRun r = t.addNewTextParagraph().addNewTextRun();
        r.setText(text);
        r.setFontSize(26.0);
        r.setBold(true);
        r.setFontColor(INK);
    }

    private void bullets(XSLFSlide s, List<String> lines) {
        XSLFTextShape t = s.createTextBox();
        t.setAnchor(new Rectangle(60, 110, 840, 380));
        for (String line : lines) {
            XSLFTextParagraph p = t.addNewTextParagraph();
            p.setBullet(true);
            XSLFTextRun r = p.addNewTextRun();
            r.setText(line);
            r.setFontSize(16.0);
            r.setFontColor(INK);
        }
    }

    private void table(XSLFSlide s, int top, String[] header, List<String[]> rows) {
        table(s, top, 840, header, rows);
    }

    private void table(XSLFSlide s, int top, int width, String[] header, List<String[]> rows) {
        XSLFTable table = s.createTable();
        table.setAnchor(new Rectangle(60, top, width, 40 * (rows.size() + 1)));
        org.apache.poi.xslf.usermodel.XSLFTableRow hr = table.addRow();
        for (String h : header) {
            XSLFTableCell c = hr.addCell();
            c.setText(h);
            c.getTextParagraphs().getFirst().getTextRuns().getFirst().setBold(true);
            c.getTextParagraphs().getFirst().getTextRuns().getFirst().setFontColor(ACCENT);
            c.setFillColor(new Color(0xF2F5F8));
        }
        for (String[] row : rows) {
            org.apache.poi.xslf.usermodel.XSLFTableRow tr = table.addRow();
            for (String v : row) {
                tr.addCell().setText(v);
            }
        }
    }

    private void footnote(XSLFSlide s, String text) {
        XSLFTextShape t = s.createTextBox();
        t.setAnchor(new Rectangle(60, 470, 840, 50));
        XSLFTextRun r = t.addNewTextParagraph().addNewTextRun();
        r.setText(text);
        r.setFontSize(11.0);
        r.setFontColor(MUTED);
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f%%", v * 100);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
