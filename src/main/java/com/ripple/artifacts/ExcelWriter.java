package com.ripple.artifacts;

import org.apache.poi.ss.usermodel.Cell;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.HorizontalAlignment;
import org.apache.poi.xssf.usermodel.XSSFSheet;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

/**
 * Excel 回测底稿（POI XSSF）：原始数据 / 指标表 / 回撤表 / 组合对比 四个核心 sheet
 * + Sources 溯源 sheet。列宽、表头样式与数值格式（百分比/小数精度）均显式设置。
 */
public final class ExcelWriter {

    /**
     * 全部指标的集中描述（供 Excel/PPT/Word 三处一致引用）。
     */
    public record Snapshot(List<String> assetOrder,
                           Map<String, PortfolioStats.Metrics> metrics,
                           Map<String, List<PortfolioStats.DrawdownEpisode>> drawdowns,
                           Map<String, Map<String, Double>> correlations,
                           List<PortfolioStats.Metrics> combos,
                           List<LocalDate> dates,
                           Map<String, List<Double>> closes,
                           Map<String, String> sources) {
    }

    private final XSSFWorkbook wb = new XSSFWorkbook();
    private final CellStyle headerStyle;
    private final CellStyle pctStyle;
    private final CellStyle num4Style;
    private final CellStyle dateStyle;

    public ExcelWriter() {
        Font bold = wb.createFont();
        bold.setBold(true);
        headerStyle = wb.createCellStyle();
        headerStyle.setFont(bold);
        headerStyle.setAlignment(HorizontalAlignment.CENTER);
        org.apache.poi.ss.usermodel.FillPatternType fill =
                org.apache.poi.ss.usermodel.FillPatternType.SOLID_FOREGROUND;
        headerStyle.setFillForegroundColor((short) 0xE8EEF4);
        headerStyle.setFillPattern(fill);
        pctStyle = wb.createCellStyle();
        pctStyle.setDataFormat(wb.createDataFormat().getFormat("0.00%"));
        num4Style = wb.createCellStyle();
        num4Style.setDataFormat(wb.createDataFormat().getFormat("0.0000"));
        dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.createDataFormat().getFormat("yyyy-mm-dd"));
    }

    public Path write(Snapshot snap, Path outFile) throws IOException {
        sheetRaw(snap);
        sheetMetrics(snap);
        sheetDrawdowns(snap);
        sheetCombos(snap);
        sheetSources(snap);
        Path parent = outFile.toAbsolutePath().getParent();
        if (parent != null) {
            java.nio.file.Files.createDirectories(parent);
        }
        try (FileOutputStream out = new FileOutputStream(outFile.toFile())) {
            wb.write(out);
        }
        wb.close();
        return outFile;
    }

    private void sheetRaw(Snapshot snap) {
        XSSFSheet sh = wb.createSheet("原始数据");
        header(sh, 0, "日期", snap.assetOrder().toArray(new String[0]));
        Map<String, List<Double>> closes = snap.closes();
        int r = 1;
        for (int i = 0; i < snap.dates().size(); i++) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(r++);
            Cell c = row.createCell(0);
            c.setCellValue(snap.dates().get(i).toString());
            int col = 1;
            for (String a : snap.assetOrder()) {
                row.createCell(col++).setCellValue(closes.get(a).get(i));
            }
        }
        for (int i = 0; i <= snap.assetOrder().size(); i++) {
            sh.setColumnWidth(i, 14 * 256);
        }
        sh.createFreezePane(1, 1);
    }

    private void sheetMetrics(Snapshot snap) {
        XSSFSheet sh = wb.createSheet("指标表");
        header(sh, 0, "资产", "起止", "交易日数", "年化收益(CAGR)", "年化波动", "最大回撤", "夏普(rf=0)");
        int r = 1;
        for (String a : snap.assetOrder()) {
            PortfolioStats.Metrics m = snap.metrics().get(a);
            org.apache.poi.ss.usermodel.Row row = sh.createRow(r++);
            row.createCell(0).setCellValue(a);
            row.createCell(1).setCellValue(m.first() + " ~ " + m.last());
            row.createCell(2).setCellValue(m.days());
            setNum(row, 3, m.cagr(), pctStyle);
            setNum(row, 4, m.annVol(), pctStyle);
            setNum(row, 5, m.maxDrawdown() / 100, pctStyle);
            setNum(row, 6, m.sharpe(), num4Style);
        }
        // 相关矩阵
        r += 1;
        org.apache.poi.ss.usermodel.Row title = sh.createRow(r++);
        Cell t = title.createCell(0);
        t.setCellValue("Pearson 相关（日收益率）");
        t.setCellStyle(headerStyle);
        List<String> names = snap.assetOrder();
        header(sh, r, "资产", names.toArray(new String[0]));
        r++;
        for (String a : names) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(r++);
            row.createCell(0).setCellValue(a);
            int col = 1;
            for (String b : names) {
                setNum(row, col++, snap.correlations().get(a).get(b), num4Style);
            }
        }
        for (int i = 0; i <= 7; i++) {
            sh.setColumnWidth(i, 15 * 256);
        }
    }

    private void sheetDrawdowns(Snapshot snap) {
        XSSFSheet sh = wb.createSheet("回撤表");
        header(sh, 0, "资产", "峰日期", "峰值", "谷日期", "谷值", "深度", "峰→谷天数");
        int r = 1;
        for (String a : snap.assetOrder()) {
            for (PortfolioStats.DrawdownEpisode e : snap.drawdowns().get(a)) {
                org.apache.poi.ss.usermodel.Row row = sh.createRow(r++);
                row.createCell(0).setCellValue(a);
                row.createCell(1).setCellValue(e.peakDate().toString());
                setNum(row, 2, e.peak(), null);
                row.createCell(3).setCellValue(e.troughDate().toString());
                setNum(row, 4, e.trough(), null);
                setNum(row, 5, e.depthPct() / 100, pctStyle);
                row.createCell(6).setCellValue(e.peakToTroughDays());
            }
        }
        for (int i = 0; i <= 6; i++) {
            sh.setColumnWidth(i, 13 * 256);
        }
    }

    private void sheetCombos(Snapshot snap) {
        XSSFSheet sh = wb.createSheet("组合对比");
        header(sh, 0, "组合", "年化收益(CAGR)", "年化波动", "最大回撤", "夏普(rf=0)", "交易日数");
        int r = 1;
        for (PortfolioStats.Metrics m : snap.combos()) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(r++);
            row.createCell(0).setCellValue(m.name());
            setNum(row, 1, m.cagr(), pctStyle);
            setNum(row, 2, m.annVol(), pctStyle);
            setNum(row, 3, m.maxDrawdown() / 100, pctStyle);
            setNum(row, 4, m.sharpe(), num4Style);
            row.createCell(5).setCellValue(m.days());
        }
        for (int i = 0; i <= 5; i++) {
            sh.setColumnWidth(i, 18 * 256);
        }
    }

    private void sheetSources(Snapshot snap) {
        XSSFSheet sh = wb.createSheet("Sources");
        header(sh, 0, "资产", "数据源", "口径说明");
        int r = 1;
        for (var e : snap.sources().entrySet()) {
            org.apache.poi.ss.usermodel.Row row = sh.createRow(r++);
            row.createCell(0).setCellValue(e.getKey());
            row.createCell(1).setCellValue(e.getValue());
            row.createCell(2).setCellValue(caliberOf(e.getKey()));
        }
        r++;
        org.apache.poi.ss.usermodel.Row note = sh.createRow(r++);
        Cell c = note.createCell(0);
        c.setCellValue("指标口径：CAGR 按日历时间；年化波动=日收益率标准差×√(每年期数)；夏普=CAGR/年化波动（rf=0）；"
                + "回撤为收盘价口径；相关性基于三资产日期交集的日收益率（Pearson）。生成于 "
                + java.time.OffsetDateTime.now().withNano(0) + "。");
        note.createCell(2);
        sh.setColumnWidth(0, 14 * 256);
        sh.setColumnWidth(1, 22 * 256);
        sh.setColumnWidth(2, 100 * 256);
    }

    private static String caliberOf(String asset) {
        if (asset.contains("BTC")) {
            return "Binance BTCUSDT 日线（7×24，USDT≈USD），未复权（无拆分分红）";
        }
        if (asset.contains("GLD")) {
            return "SPDR 黄金 ETF（GLD）日线，前复权；为黄金价格代理而非现货金";
        }
        return "SPDR 标普 500 ETF（SPY）日线，前复权；股市代理";
    }

    private void header(XSSFSheet sh, int rowIndex, String first, String... rest) {
        String[] cols = new String[rest.length + 1];
        cols[0] = first;
        System.arraycopy(rest, 0, cols, 1, rest.length);
        org.apache.poi.ss.usermodel.Row row = sh.createRow(rowIndex);
        for (int i = 0; i < cols.length; i++) {
            Cell c = row.createCell(i);
            c.setCellValue(cols[i]);
            c.setCellStyle(headerStyle);
        }
    }

    private static void setNum(org.apache.poi.ss.usermodel.Row row, int col, double v, CellStyle style) {
        Cell c = row.createCell(col);
        c.setCellValue(v);
        if (style != null) {
            c.setCellStyle(style);
        }
    }
}
