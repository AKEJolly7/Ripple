package com.ripple.domain;

import java.time.LocalDate;

/**
 * 单根日 K 线。价格为前复权口径（由 Yahoo adjclose/close 因子统一缩放，
 * 保证 NVDA 2024-06 10:1 拆股前后序列连续）。
 */
public record Ohlcv(LocalDate date, double open, double high, double low, double close, long volume) {
}
