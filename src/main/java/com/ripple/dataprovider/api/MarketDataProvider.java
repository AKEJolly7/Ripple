package com.ripple.dataprovider.api;

import com.ripple.dataprovider.HttpFetchException;

import com.ripple.domain.Ohlcv;

import java.time.LocalDate;
import java.util.List;

/**
 * 行情数据源抽象（R1 实现 Yahoo，未来可换源）。
 */
public interface MarketDataProvider {

    /**
     * 拉取 [start, end] 闭区间的日线 OHLCV（前复权口径）。
     */
    List<Ohlcv> fetchDaily(String symbol, LocalDate start, LocalDate end)
            throws HttpFetchException, InterruptedException;
}
