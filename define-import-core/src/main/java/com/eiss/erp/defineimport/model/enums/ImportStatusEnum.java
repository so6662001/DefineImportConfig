package com.eiss.erp.defineimport.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 导入状态枚举
 */
@Getter
@AllArgsConstructor
public enum ImportStatusEnum {
    PREVIEWING(0, "预览中"),
    CONFIRMED(1, "已确认"),
    ROLLBACK(2, "已回滚");

    private final int code;
    private final String desc;

    public static ImportStatusEnum fromCode(int code) {
        for (ImportStatusEnum e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的导入状态: " + code);
    }
}
