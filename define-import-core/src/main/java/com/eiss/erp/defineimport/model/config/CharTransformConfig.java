package com.eiss.erp.defineimport.model.config;

import java.util.List;

import lombok.Data;

/**
 * 字符转换配置
 */
@Data
public class CharTransformConfig {
    private Boolean useTemplateRules;
    private List<String> usePresetGroups;
    private List<CharRule> fieldRules;
    private List<String> excludePresetCodes;

    @Data
    public static class CharRule {
        /** LITERAL, REGEX, FULLWIDTH, CHARCLASS */
        private String matchType;
        private String matchPattern;
        private String replaceValue;
        private Integer sortOrder;
        private String description;
        /** Comma-separated field codes; when set, rule applies only to those fields (see pipeline build). */
        private String applyFieldCodes;
        /** When set, match/replace may be resolved from import_char_rule_preset at load time. */
        private Long presetRuleId;
        /** Stable code for preset rules (e.g. from import_char_rule_preset.rule_code); used for excludePresetCodes. */
        private String ruleCode;
    }
}
