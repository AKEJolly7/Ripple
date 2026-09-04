package com.ripple.domain.enums;

/** 趋势段标签。枚举常量英文命名，中文标签经 label() 输出到产物。 */
public enum TrendLabel {
    UP("上涨"),
    DOWN("下跌"),
    SIDEWAYS("盘整");

    private final String label;

    TrendLabel(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
