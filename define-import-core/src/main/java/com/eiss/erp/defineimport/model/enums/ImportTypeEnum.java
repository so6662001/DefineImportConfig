package com.eiss.erp.defineimport.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 导入类型枚举
 */
@Getter
@AllArgsConstructor
public enum ImportTypeEnum {
    MIXED(0, "混合"),
    INVENTORY_ONLY(1, "仅库存"),
    PRICE_ONLY(2, "仅价格");

    private final int code;
    private final String desc;

    public static ImportTypeEnum fromCode(int code) {
        for (ImportTypeEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的导入类型: " + code);
    }
}
