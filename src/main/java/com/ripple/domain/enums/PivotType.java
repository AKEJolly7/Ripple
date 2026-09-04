package com.ripple.domain.enums;

/** 拐点类型。枚举常量英文命名，中文标签经 label() 输出到产物。 */
public enum PivotType {
    LOCAL_TOP("局部高点"),
    LOCAL_BOTTOM("局部低点"),
    GOLDEN_CROSS("均线金叉"),
    DEATH_CROSS("均线死叉"),
    BIG_UP("显著上涨"),
    BIG_DOWN("显著下跌");

    private final String label;

    PivotType(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
