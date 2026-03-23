package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.entity.ImportTemplateFieldValueMapping;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;

/**
 * 通用字段值映射处理器 (v1.2 泛化, v1.7 扩展remark)
 * <p>
 * 支持品类(category)、产地(origin)、材质(material)、备注(remark)四个字段。
 * 映射规则按 sortOrder 升序排列，首个匹配的规则生效。
 * </p>
 * <p>
 * 匹配逻辑：
 * <ul>
 *   <li>sourceValue 为 null → 匹配任意原始值（通配）</li>
 *   <li>qualifier 为 null → 匹配任意限定符（通配）</li>
 *   <li>两项均匹配时命中该规则，返回 targetValue</li>
 * </ul>
 * </p>
 */
public class FieldValueMapper {

    private FieldValueMapper() {
    }

    /**
     * 根据映射规则对字段值进行映射。
     *
     * @param targetField 目标字段代码 (category/origin/material/remark)
     * @param rawValue    Excel 中读取的原始值（可能为 null）
     * @param qualifier   限定文本，如表头文本 "黑材"（可能为 null）
     * @param mappings    映射规则列表
     * @return 映射后的值；无规则命中时原样返回 rawValue
     */
    public static String map(String targetField, String rawValue, String qualifier,
                              List<ImportTemplateFieldValueMapping> mappings) {
        if (mappings == null || mappings.isEmpty()) {
            return rawValue;
        }

        // 按 sortOrder 升序排列后，取首个匹配的规则
        return mappings.stream()
                .filter(m -> m != null && Objects.equals(targetField, m.getTargetField()))
                .sorted(Comparator.comparingInt(m -> m.getSortOrder() == null ? Integer.MAX_VALUE : m.getSortOrder()))
                .filter(m -> matchesSourceValue(m.getSourceValue(), rawValue)
                        && matchesQualifier(m.getQualifier(), qualifier))
                .map(ImportTemplateFieldValueMapping::getTargetValue)
                .findFirst()
                .orElse(rawValue);
    }

    /**
     * sourceValue 为 null 表示通配（匹配任意值），否则要求精确相等（trim 后比较）。
     */
    private static boolean matchesSourceValue(String ruleSource, String rawValue) {
        if (ruleSource == null) {
            return true;
        }
        if (rawValue == null) {
            return false;
        }
        return ruleSource.trim().equals(rawValue.trim());
    }

    /**
     * qualifier 为 null 表示通配（匹配任意限定符），否则要求精确相等（trim 后比较）。
     */
    private static boolean matchesQualifier(String ruleQualifier, String inputQualifier) {
        if (ruleQualifier == null) {
            return true;
        }
        if (inputQualifier == null) {
            return false;
        }
        return ruleQualifier.trim().equals(inputQualifier.trim());
    }
}
