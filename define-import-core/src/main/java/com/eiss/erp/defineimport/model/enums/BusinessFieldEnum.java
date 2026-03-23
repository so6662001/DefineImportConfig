package com.eiss.erp.defineimport.model.enums;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * 业务字段枚举
 */
@Getter
@AllArgsConstructor
public enum BusinessFieldEnum {
    CATEGORY("category", "品类"),
    SPEC("spec", "规格"),
    ORIGIN("origin", "产地"),
    MATERIAL("material", "材质"),
    PACKAGE_NUM("package_num", "包装数量"),
    WHOLE_NUM("whole_num", "整件数"),
    ODD_NUM("odd_num", "零数"),
    WEIGHT("weight", "重量"),
    PRICE("price", "单价"),
    WALL_THICKNESS("wall_thickness", "壁厚"),
    REMARK("remark", "备注");

    private final String code;
    private final String desc;

    /**
     * 是否支持值映射（如字典、主数据映射）。
     */
    public boolean isValueMappable() {
        return this == CATEGORY || this == ORIGIN || this == MATERIAL || this == REMARK;
    }

    public static BusinessFieldEnum fromCode(String code) {
        if (code == null) {
            throw new IllegalArgumentException("业务字段代码不能为空");
        }
        for (BusinessFieldEnum e : values()) {
            if (e.code.equals(code)) {
                return e;
            }
        }
        throw new IllegalArgumentException("未知的业务字段: " + code);
    }
}
