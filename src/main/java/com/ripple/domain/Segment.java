package com.ripple.domain;

import com.ripple.domain.enums.TrendLabel;

import java.time.LocalDate;

/** 趋势段：按回撤阈值切分的连续区间。maxDrawdownPct 为段内峰到谷最大回撤。 */
public record Segment(
        LocalDate startDate,
        LocalDate endDate,
        TrendLabel label,
        double startPrice,
        double endPrice,
        double maxDrawdownPct) {

    /** 段净涨跌幅（%）。 */
    public double netChangePct() {
        return (endPrice - startPrice) / startPrice * 100;
    }
}
