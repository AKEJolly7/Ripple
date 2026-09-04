package com.ripple.dataprovider;

import com.ripple.domain.NewsItem;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.LocalDate;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 用 mock 的 Algolia 响应验证解析与溯源字段（objectID / story_url / 回退链接），不访问真实网络。
 */
class HackerNewsProviderTest {

    private static final String OK_BODY = """
            {"hits":[
              {"objectID":"33890288","title":"OpenAI releases ChatGPT",
               "url":"https://openai.com/blog/chatgpt",
               "created_at":"2022-11-30T13:22:00Z",
               "points":1500,"num_comments":900,"story_text":null},
              {"objectID":"99999999","title":"Ask HN: What do you think about Nvidia GPUs?",
               "url":null,"story_url":null,
               "created_at":"2023-05-10T08:00:00Z",
               "points":5,"num_comments":12,"story_text":"%s"}],
             "nbHits":2}""".formatted("Long story text ".repeat(30));

    @Test
    void parsesHitsKeepsTraceabilityAndFallsBackToItemLink() throws Exception {
        var fake = new FakeTransport(List.of(200), OK_BODY);
        var provider = new HackerNewsProvider(fake);

        List<NewsItem> items = provider.search("ChatGPT",
                LocalDate.of(2022, 11, 1), LocalDate.of(2023, 6, 1));

        assertEquals(2, items.size());

        NewsItem first = items.getFirst();
        assertEquals("OpenAI releases ChatGPT", first.title());
        assertEquals(LocalDate.of(2022, 11, 30), first.date());
        assertEquals("https://openai.com/blog/chatgpt", first.url());
        assertEquals("33890288", first.externalId());
        assertEquals("HackerNews", first.source());

        // 无外链的 Ask HN → 回退到 HN item 页，保证可溯源；story_text 超长被截断
        NewsItem second = items.get(1);
        assertEquals("https://news.ycombinator.com/item?id=99999999", second.url());
        assertTrue(second.summary().endsWith("…"));
        assertTrue(second.summary().length() <= 301);
    }

    @Test
    void buildsTimeWindowAndKeywordIntoQuery() throws Exception {
        var fake = new FakeTransport(List.of(200), "{\"hits\":[]}");
        new HackerNewsProvider(fake).search("Nvidia AI",
                LocalDate.of(2024, 1, 1), LocalDate.of(2024, 12, 31));

        URI uri = fake.lastUri();
        String q = uri.toString();
        assertTrue(q.startsWith("https://hn.algolia.com/api/v1/search_by_date?"), q);
        assertTrue(q.contains("query=Nvidia+AI"), q);
        // 2024-01-01T00:00Z = 1704067200；2025-01-01T00:00Z = 1735689600（编码后的时间窗）
        assertTrue(q.contains("created_at_i%3E1704067200"), q);
        assertTrue(q.contains("created_at_i%3C1735689600"), q);
        assertTrue(q.contains("tags=story"), q);
    }
}
