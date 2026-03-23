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
    }
}
