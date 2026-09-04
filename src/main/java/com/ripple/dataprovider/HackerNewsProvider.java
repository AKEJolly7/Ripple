package com.ripple.dataprovider;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ripple.dataprovider.api.HttpTransport;
import com.ripple.dataprovider.api.NewsProvider;
import com.ripple.domain.NewsItem;
import org.apache.logging.log4j.util.Strings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Hacker News Algolia 检索实现。
 * https://hn.algolia.com/api/v1/search_by_date?query=<kw>&tags=story
 * &numericFilters=created_at_i><start>,created_at_i><end>&hitsPerPage=200
 * <p>
 * 溯源：保留 objectID（externalId）与原文链接（url/story_url）；
 * 无外链的 Ask HN 等回退到 HN item 页，保证每条都有可点击来源。
 */
public final class HackerNewsProvider implements NewsProvider {

    private static final Logger log = LoggerFactory.getLogger(HackerNewsProvider.class);

    private final HttpTransport transport;
    private final ObjectMapper mapper = new ObjectMapper();

    public HackerNewsProvider() {
        this(new ResilientHttpTransport());
    }

    public HackerNewsProvider(HttpTransport transport) {
        this.transport = transport;
    }

    @Override
    public List<NewsItem> search(String keyword, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException {
        long startSec = start.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        long endSec = end.plusDays(1).atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        String enc = URLEncoder.encode(keyword, StandardCharsets.UTF_8);
        String filters = URLEncoder.encode(
                "created_at_i>" + startSec + ",created_at_i<" + endSec, StandardCharsets.UTF_8);
        URI uri = URI.create("https://hn.algolia.com/api/v1/search_by_date?query=" + enc
                + "&tags=story&numericFilters=" + filters + "&hitsPerPage=200");

        HttpTransport.Response resp = transport.get(uri);
        if (resp.status() != 200) {
            throw HttpFetchException.ofStatus(resp.status());
        }
        List<NewsItem> items = parse(resp.body());
        log.info("HN Algolia \"{}\" [{} ~ {}] 命中 {} 条", keyword, start, end, items.size());
        return items;
    }

    private List<NewsItem> parse(String body) throws HttpFetchException {
        JsonNode root;
        try {
            root = mapper.readTree(body);
        } catch (Exception e) {
            throw new HttpFetchException(HttpFetchException.Type.PARSE, "响应不是合法 JSON", e);
        }
        List<NewsItem> out = new ArrayList<>();
        for (JsonNode hit : root.path("hits")) {
            String title = hit.path("title").asText(null);
            String objectID = hit.path("objectID").asText(null);
            if (title == null || objectID == null) {
                continue;
            }
            String created = hit.path("created_at").asText(null);
            LocalDate date = (created == null) ? null
                    : Instant.parse(created).atZone(ZoneOffset.UTC).toLocalDate();
            String url = firstNonNull(hit.path("url").asText(null),
                    hit.path("story_url").asText(null));
            if (Strings.isBlank(url)) {
                url = "https://news.ycombinator.com/item?id=" + objectID;
            }
            String summary = hit.path("story_text").asText("");
            if (summary != null && summary.length() > 300) {
                summary = summary.substring(0, 300) + "…";
            }
            out.add(new NewsItem(title, date, summary == null ? "" : summary, url, "HackerNews",
                    objectID));
        }
        // Algolia 默认新→旧，统一改为时间升序，使"首/末日期"语义正确
        out.sort(Comparator.comparing(NewsItem::date,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return out;
    }

    private static String firstNonNull(String a, String b) {
        return Strings.isBlank(a) ? b : a;
    }
}
