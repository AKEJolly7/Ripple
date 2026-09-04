package com.ripple.dataprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.dataprovider.api.HttpTransport;
import com.ripple.dataprovider.api.MarketDataProvider;
import com.ripple.domain.Ohlcv;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 新浪美股/ETF 日线兜底数据源（Yahoo 地域封锁 403 时的降级路径）。
 * https://stock.finance.sina.com.cn/usstock/api/jsonp_v2.php/var t=/US_MinKService.getDailyK?symbol=nvda
 * <p>
 * 返回为未复权原始价 + 全量历史，本类负责：
 * ① 按 [start, end] 窗口本地过滤；② 按拆股表做前复权（除权日之前的 K 线价格 ÷ratio、成交量 ×ratio，
 * 使拆股前后序列连续，与 Yahoo adjclose 口径一致）。
 */
public final class SinaFinanceProvider implements MarketDataProvider {

    private static final Logger log = LoggerFactory.getLogger(SinaFinanceProvider.class);
    private static final Pattern JSONP_BODY = Pattern.compile("\\((.*)\\)", Pattern.DOTALL);

    /**
     * 拆股表：exDate=除权日，ratio=1 股旧股折新股数。仅维护本项目用到的标的。
     */
    private record Split(LocalDate exDate, double ratio) {
    }

    private static final Map<String, List<Split>> SPLITS = Map.of(
            "NVDA", List.of(
                    new Split(LocalDate.of(2021, 7, 20), 4.0),   // 4:1
                    new Split(LocalDate.of(2024, 6, 10), 10.0))); // 10:1

    private final HttpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    public SinaFinanceProvider() {
        this(new ResilientHttpTransport());
    }

    public SinaFinanceProvider(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<Ohlcv> fetchDaily(String symbol, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException {
        String sinaSymbol = symbol.toLowerCase(Locale.ROOT);
        int dash = sinaSymbol.indexOf('-');
        if (dash > 0) {
            sinaSymbol = sinaSymbol.substring(0, dash);
        }
        URI uri = URI.create("https://stock.finance.sina.com.cn/usstock/api/jsonp_v2.php/"
                + "var%20t=/US_MinKService.getDailyK?symbol=" + sinaSymbol + "&___qn=3");

        HttpTransport.Response resp = transport.get(uri);
        if (resp.status() != 200) {
            throw HttpFetchException.ofStatus(resp.status());
        }
        List<Split> splits = SPLITS.getOrDefault(symbol.toUpperCase(Locale.ROOT), List.of());
        List<Ohlcv> candles = parse(resp.body(), start, end, splits);
        log.info("Sina {} [{} ~ {}] 过滤后 {} 根日 K（拆股调整 {} 次）",
                symbol, start, end, candles.size(), splits.size());
        return candles;
    }

    private List<Ohlcv> parse(String body, LocalDate start, LocalDate end, List<Split> splits)
            throws HttpFetchException {
        Matcher m = JSONP_BODY.matcher(body);
        if (!m.find()) {
            throw new HttpFetchException(HttpFetchException.Type.PARSE, "Sina JSONP 响应格式异常");
        }
        JsonNode arr;
        try {
            arr = mapper.readTree(m.group(1));
        } catch (Exception e) {
            throw new HttpFetchException(HttpFetchException.Type.PARSE, "Sina 响应不是合法 JSON", e);
        }
        if (!arr.isArray() || arr.isEmpty()) {
            // Sina 恒返回全量历史：空数组只可能是 symbol 不存在
            throw new HttpFetchException(HttpFetchException.Type.CLIENT,
                    "Sina 未返回数据（symbol 可能不存在）");
        }
        List<Ohlcv> out = new ArrayList<>();
        for (JsonNode row : arr) {
            LocalDate date = LocalDate.parse(row.path("d").asText());
            if (date.isBefore(start) || date.isAfter(end)) {
                continue;
            }
            double factor = splits.stream()
                    .filter(s -> date.isBefore(s.exDate()))
                    .mapToDouble(Split::ratio)
                    .reduce(1.0, (a, b) -> a * b);
            out.add(new Ohlcv(date,
                    row.path("o").asDouble() / factor,
                    row.path("h").asDouble() / factor,
                    row.path("l").asDouble() / factor,
                    row.path("c").asDouble() / factor,
                    Math.round(row.path("v").asDouble() * factor)));
        }
        return out;
    }
}
