package com.eiss.erp.defineimport.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 数据来源类型枚举
 */
@Getter
@AllArgsConstructor
public enum SourceTypeEnum {
    COLUMN("COLUMN", "数据列"),
    FIXED_CELL("FIXED_CELL", "固定单元格"),
    FIXED_VALUE("FIXED_VALUE", "固定值"),
    COMPOSITE("COMPOSITE", "多源组合"),
    COLUMN_HEADER("COLUMN_HEADER", "列表头");

    private final String code;
    private final String desc;

    public static SourceTypeEnum fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("数据来源类型代码不能为空");
        }
        for (SourceTypeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的数据来源类型: " + code);
    }
}
