package com.ripple.artifacts;

import org.apache.poi.xssf.usermodel.XSSFCellStyle;
import org.apache.poi.xssf.usermodel.XSSFColor;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Excel 表头样式回归：setFillForegroundColor(short) 的参数是调色板索引（合法 0-81），
 * 曾误传 RGB 十六进制 0xE8EEF4 截断成 indexed=-4364——非法索引的实心填充被
 * Excel/WPS 渲染成黑底（用户实开指标表发现）。固化不变式：表头填充必须是合法 RGB。
 */
class ExcelStyleTest {

    @Test
    void headerFillIsRgbNotInvalidIndexed(@TempDir Path dir) throws Exception {
        Path out = dir.resolve("t.xlsx");
        new ExcelWriter().write(TestSnapshots.minimal(), out);

        try (XSSFWorkbook wb = new XSSFWorkbook(out.toFile())) {
            XSSFCellStyle header = wb.getCellStyleAt(1);
            XSSFColor color = header.getFillForegroundXSSFColor();
            assertNotNull(color, "表头填充色缺失");
            assertNotNull(color.getRGB(), "表头填充色是 indexed 而非 RGB——黑底缺陷回归");
            byte[] rgb = color.getRGB();
            assertTrue(rgb.length >= 3
                            && (rgb[rgb.length - 3] & 0xFF) == 0xE8
                            && (rgb[rgb.length - 2] & 0xFF) == 0xEE
                            && (rgb[rgb.length - 1] & 0xFF) == 0xF4,
                    "表头填充色应为 E8EEF4，实际 " + java.util.Arrays.toString(rgb));
        }
    }
}
