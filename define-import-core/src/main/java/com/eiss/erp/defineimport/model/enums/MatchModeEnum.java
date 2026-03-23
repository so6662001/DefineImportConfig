package com.eiss.erp.defineimport.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 列匹配模式枚举
 */
@Getter
@AllArgsConstructor
public enum MatchModeEnum {
    INDEX("INDEX", "按列索引"),
    ALIAS("ALIAS", "按表头别名"),
    INDEX_FIRST("INDEX_FIRST", "索引优先");

    private final String code;
    private final String desc;

    public static MatchModeEnum fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("列匹配模式代码不能为空");
        }
        for (MatchModeEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的列匹配模式: " + code);
    }
}
