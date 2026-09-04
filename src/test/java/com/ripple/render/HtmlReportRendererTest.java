package com.ripple.render;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.ripple.domain.EventMark;
import com.ripple.domain.enums.ImpactRating;
import com.ripple.domain.enums.LinkStrength;
import com.ripple.domain.MissingEvent;
import com.ripple.domain.NewsItem;
import com.ripple.domain.Ohlcv;
import com.ripple.domain.enums.PivotType;
import com.ripple.domain.Segment;
import com.ripple.domain.enums.TrendLabel;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 解析生成的 HTML 断言：事件标记存在、href 全部以 http 开头、
 * 无外链 script src、&lt;/script&gt; 注入被转义、占位符全部替换。无网络。
 */
class HtmlReportRendererTest {

    @TempDir
    Path tmp;

    private static final LocalDate D1 = LocalDate.of(2025, 1, 27);
    private static final LocalDate D2 = LocalDate.of(2023, 5, 25);

    @Test
    void rendersSingleFileWithTraceableMarksAndNoExternalRefs() throws Exception {
        writeWorkFiles();

        var result = new HtmlReportRenderer().render(List.of("NVDA"), tmp,
                tmp.resolve("out.html"));
        String html = Files.readString(result.file(), StandardCharsets.UTF_8);

        // ① 事件标记（markPoint 数据）存在：静态面板 mark 行 + 内嵌 JSON 的 pivotDate
        int markRows = count(html, "class=\"mark\"");
        assertTrue(markRows >= 2, "事件标记数量应 >0，实际 " + markRows);
        assertTrue(count(html, "\"pivotDate\"") >= 2);

        // ② 每个静态 href 以 http 开头（来源链接可溯源）。
        // 以 ' 开头的命中是内嵌 JS 的字符串拼接片段（href="' + esc(m.url) + '"），
        // 其运行时取值来自与静态面板相同的已校验数据，非页面静态链接。
        Matcher href = Pattern.compile("href=\"([^\"]*)\"").matcher(html);
        int hrefCount = 0;
        while (href.find()) {
            String v = href.group(1);
            if (v.startsWith("'")) {
                continue;   // JS 模板代码，非静态链接
            }
            hrefCount++;
            assertTrue(v.startsWith("http"), "非法 href: " + v);
        }
        assertTrue(hrefCount >= 3, "静态来源链接数应 ≥3，实际 " + hrefCount);

        // ③ 无外链资源加载（CDN/供应链风险为零，断网可开）。
        // 注意：禁止的是资源标签的 src/href（script/img/link/iframe 等会发起请求），
        // 溯源面板的 <a href="http..."> 是导航锚点（需求要求可点击溯源），不属此列。
        assertFalse(Pattern.compile("<script[^>]*\\ssrc\\s*=", Pattern.CASE_INSENSITIVE)
                .matcher(html).find(), "存在带 src 的 <script>（应为纯内嵌）");
        assertFalse(Pattern
                .compile("<(script|img|link|iframe|source|video|audio|object|embed)[^>]*"
                        + "\\s(src|href)\\s*=\\s*[\"']https?://", Pattern.CASE_INSENSITIVE)
                .matcher(html).find(), "存在资源标签的 http(s) 外链引用");

        // ④ 标题含 </script> 的注入被转义（JSON 内嵌 <\\/）
        assertTrue(html.contains("<\\/"), "未对 </ 做转义，存在 script 注入截断风险");

        // ⑤ 模板占位符全部替换、ECharts 已内嵌、缺失标注渲染
        assertFalse(html.contains("__DATA_JSON__") || html.contains("__ECHARTS_JS__")
                || html.contains("__PANEL_SECTIONS__") || html.contains("__FOOTER__"));
        assertTrue(html.contains("echarts"));
        assertTrue(html.contains("事件缺失"));

        // ⑥ 单文件规模合理（echarts ~1.1MB + 数据）
        assertTrue(result.bytes() > 1_100_000 && result.bytes() < 16_000_000);
    }

    /**
     * 构造合成 work 检查点：70 根 K 线 + 趋势段 + 2 条归因（其一标题含 </script> 注入串）+ 1 个缺失。
     */
    private void writeWorkFiles() throws Exception {
        ObjectMapper m = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        List<Ohlcv> candles = new ArrayList<>();
        LocalDate d = LocalDate.of(2024, 1, 1);
        for (int i = 0; i < 70; i++) {
            double base = 100 + i * 0.5 + (i % 7 == 0 ? 2 : 0);
            candles.add(new Ohlcv(d.plusDays(i), base - 1, base + 2, base - 2, base, 1_000_000L + i));
        }
        m.writerWithDefaultPrettyPrinter().writeValue(
                tmp.resolve("NVDA_ohlcv.json").toFile(),
                Map.of("symbol", "NVDA", "source", "TestSource", "candles", candles));
        m.writerWithDefaultPrettyPrinter().writeValue(
                tmp.resolve("NVDA_analysis.json").toFile(),
                Map.of("segments", List.of(
                        new Segment(LocalDate.of(2024, 1, 10), LocalDate.of(2024, 2, 1),
                                TrendLabel.UP, 104.0, 128.0, 5.0))));

        EventMark injected = new EventMark("Evil </script>alert(1) title", D1,
                "https://example.com/injected", "HackerNews", "摘要注入 </script> 测试",
                D1, PivotType.BIG_DOWN, -16.97, true, 0.92, LinkStrength.STRONG,
                ImpactRating.BEARISH, 0.9, "推理一");
        EventMark normal = new EventMark("Nvidia Q1 2023 earnings blowout", D2,
                "https://example.com/earnings", "HackerNews", "财报大超预期",
                D2, PivotType.BIG_UP, 24.37, true, 1.0, LinkStrength.STRONG,
                ImpactRating.BULLISH, 0.95, "推理二");
        MissingEvent missing = new MissingEvent(LocalDate.of(2024, 3, 1), PivotType.BIG_DOWN,
                -8.0, false, List.of(new NewsItem("Some candidate story", LocalDate.of(2024, 3, 1),
                "", "https://example.com/cand", "HackerNews", "id-9")));
        m.writerWithDefaultPrettyPrinter().writeValue(
                tmp.resolve("NVDA_alignments.json").toFile(),
                Map.of("symbol", "NVDA", "mode", "rule", "schemaVersion", "2",
                        "marks", List.of(injected, normal), "missing", List.of(missing)));
    }

    private static int count(String html, String needle) {
        int c = 0;
        for (int i = html.indexOf(needle); i >= 0; i = html.indexOf(needle, i + 1)) {
            c++;
        }
        return c;
    }
}
