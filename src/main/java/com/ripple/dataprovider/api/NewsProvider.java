package com.ripple.dataprovider.api;

import com.ripple.dataprovider.HttpFetchException;

import com.ripple.domain.NewsItem;

import java.time.LocalDate;
import java.util.List;

/**
 * 资讯数据源抽象（R1 实现 HN Algolia，GDELT 兜底在后续里程碑接入）。
 */
public interface NewsProvider {

    /**
     * 按关键词检索 [start, end] 时间窗内的资讯，每条带原文 URL（溯源凭据）。
     */
    List<NewsItem> search(String keyword, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException;
}
