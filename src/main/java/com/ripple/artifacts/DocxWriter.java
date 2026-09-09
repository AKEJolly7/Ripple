package com.ripple.artifacts;

import org.apache.poi.xwpf.usermodel.*;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

/**
 * Word 策略报告（POI XWPF）：结论先行 → 指标支撑 → 数据口径 → 风险提示 → 附录回测表。
 */
public final class DocxWriter {

    public DocxWriter() {
    }

    public Path write(ExcelWriter.Snapshot snap, Path outFile) throws IOException {
        try (XWPFDocument doc = new XWPFDocument()) {
            var gldName = snap.assetOrder().stream().filter(a -> a.contains("GLD")).findFirst().orElseThrow();
            var btcName = snap.assetOrder().stream().filter(a -> a.contains("BTC")).findFirst().orElseThrow();
            var gld = snap.metrics().get(gldName);
            var btc = snap.metrics().get(btcName);
            double corr = snap.correlations().get(gldName).get(btcName);

            heading(doc, "黄金与比特币：避险/抗通胀配置策略报告", 0);
            para(doc, "观澜（Ripple）· 观其澜，知其源 —— 本报告由确定性回测流水线生成，"
                    + "全部数值可在 Excel 回测底稿（gold-btc-backtest.xlsx）交叉核对。", false, true);

            heading(doc, "一、结论（先行）", 1);
            para(doc, "1. 黄金与比特币不是替代关系而是互补关系：黄金是低波动避险压舱石"
                    + "（年化波动 " + pct(gld.annVol()) + "，最大回撤 " + pct(gld.maxDrawdown() / 100)
                    + "，CAGR " + pct(gld.cagr()) + "），比特币是高波动高风险偏好的增强器"
                    + "（年化波动 " + pct(btc.annVol()) + "，最大回撤 " + pct(btc.maxDrawdown() / 100)
                    + "，CAGR " + pct(btc.cagr()) + "）。", false, false);
            para(doc, "2. 两者日收益率 Pearson 相关仅 " + num(corr)
                    + "，同仓配置具备分散化价值；60/40（金/币）等组合情景见附录与 Excel 组合对比 sheet。", false, false);
            para(doc, "3. 配置建议：以黄金为核心避险仓位（波动预算的大头），比特币作为卫星仓位"
                    + "严格控制回撤容忍度与仓位上限；单一固定权重非最优解，按风险贡献再平衡。", false, false);

            heading(doc, "二、指标支撑", 1);
            heading(doc, "三、数据口径", 1);
            bullets(doc, List.of(
                    "样本窗口：" + snap.dates().get(0) + " ~ " + snap.dates().get(snap.dates().size() - 1)
                            + "（三资产日期交集 " + snap.dates().size() + " 个交易日）",
                    "行情来源：" + gldName + "=" + snap.sources().get(gldName)
                            + "；" + btcName + "=" + snap.sources().get(btcName)
                            + "（原始 JSON 落盘 work/ 可溯源）",
                    "CAGR 按日历时间复合；年化波动=日收益率标准差×√(每年期数，BTC≈365、GLD/SPY≈252)；"
                            + "夏普=CAGR/年化波动（rf=0 简化口径）；回撤为收盘价口径",
                    "组合情景基于交集日收益率线性加权，逐日再平衡假设"));

            heading(doc, "四、风险提示", 1);
            bullets(doc, List.of(
                    "历史回测不代表未来表现；五年样本仅覆盖一轮完整牛熊，未包含更早的极端事件",
                    "GLD 为 ETF 价格代理（含管理费拖累），与现货金存在细微偏离；BTC 数据以 USDT 计价≈USD",
                    "相关性在危机中可能失效趋同（tail correlation 上升），分散化保护会被高估",
                    "监管、托管与流动性风险（尤其比特币）不在本回测范围内"));

            heading(doc, "附录：回测指标表", 1);
            XWPFTable table = doc.createTable(1, 6);
            setRow(table.getRow(0), List.of("资产", "年化收益", "年化波动", "最大回撤", "夏普(rf=0)", "交易日数"), true);
            for (String a : snap.assetOrder()) {
                var m = snap.metrics().get(a);
                XWPFTableRow row = table.createRow();
                setRow(row, List.of(a, pct(m.cagr()), pct(m.annVol()),
                        pct(m.maxDrawdown() / 100), num(m.sharpe()), String.valueOf(m.days())), false);
            }
            para(doc, "", false, false);
            para(doc, "组合情景（详见 Excel 组合对比 sheet）：", false, true);
            XWPFTable comboTable = doc.createTable(1, 5);
            setRow(comboTable.getRow(0), List.of("组合（金/币/股）", "年化收益", "年化波动", "最大回撤", "夏普"), true);
            for (var m : snap.combos()) {
                XWPFTableRow row = comboTable.createRow();
                setRow(row, List.of(m.name(), pct(m.cagr()), pct(m.annVol()),
                        pct(m.maxDrawdown() / 100), num(m.sharpe())), false);
            }

            Path parent = outFile.toAbsolutePath().getParent();
            if (parent != null) {
                java.nio.file.Files.createDirectories(parent);
            }
            try (FileOutputStream out = new FileOutputStream(outFile.toFile())) {
                doc.write(out);
            }
        }
        return outFile;
    }

    // ------------------------------------------------------------------
    // 版式工具
    // ------------------------------------------------------------------

    private void heading(XWPFDocument doc, String text, int level) {
        XWPFParagraph p = doc.createParagraph();
        if (level == 0) {
            p.setAlignment(ParagraphAlignment.CENTER);
        }
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setBold(true);
        r.setFontSize(level == 0 ? 20 : 14);
    }

    private void para(XWPFDocument doc, String text, boolean bold, boolean italic) {
        XWPFParagraph p = doc.createParagraph();
        XWPFRun r = p.createRun();
        r.setText(text);
        r.setFontSize(11);
        r.setBold(bold);
        r.setItalic(italic);
    }

    private void bullets(XWPFDocument doc, List<String> lines) {
        for (String line : lines) {
            XWPFParagraph p = doc.createParagraph();
            XWPFRun r = p.createRun();
            r.setText("· " + line);
            r.setFontSize(11);
        }
    }

    private void setRow(XWPFTableRow row, List<String> cells, boolean bold) {
        for (int i = 0; i < cells.size(); i++) {
            var c = row.getCell(i);
            c.setText(cells.get(i));
            var runs = c.getParagraphs().get(0).getRuns();
            if (!runs.isEmpty()) {
                runs.get(0).setBold(bold);
                runs.get(0).setFontSize(10);
            }
        }
    }

    private static String pct(double v) {
        return String.format(Locale.ROOT, "%.2f%%", v * 100);
    }

    private static String num(double v) {
        return String.format(Locale.ROOT, "%.4f", v);
    }
}
