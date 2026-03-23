package com.eiss.erp.defineimport.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 回写模式枚举
 */
@Getter
@AllArgsConstructor
public enum WritebackModeEnum {
    UPDATE_EMPTY_ONLY("UPDATE_EMPTY_ONLY", "仅更新空值"),
    UPDATE_ALL("UPDATE_ALL", "全部更新"),
    UPDATE_IF_CHANGED("UPDATE_IF_CHANGED", "有变更时更新");

    private final String code;
    private final String desc;

    public static WritebackModeEnum fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("回写模式代码不能为空");
        }
        for (WritebackModeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的回写模式: " + code);
    }
}
