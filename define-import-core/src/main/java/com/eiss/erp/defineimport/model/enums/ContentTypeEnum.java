package com.eiss.erp.defineimport.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 内容类型枚举
 */
@Getter
@AllArgsConstructor
public enum ContentTypeEnum {
    INVENTORY(1, "库存"),
    PRICE(2, "价格");

    private final int code;
    private final String desc;

    public static ContentTypeEnum fromCode(int code) {
        for (ContentTypeEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的内容类型: " + code);
    }
}
