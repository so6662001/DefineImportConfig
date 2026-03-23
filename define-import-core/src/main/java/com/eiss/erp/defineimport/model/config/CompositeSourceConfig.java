package com.eiss.erp.defineimport.model.config;

import java.util.List;

import lombok.Data;

/**
 * COMPOSITE 来源类型：多源组合配置
 */
@Data
public class CompositeSourceConfig {
    private List<CompositePart> parts;
    private String separator;
    /** 如 "${0}${1}${2}" */
    private String template;

    @Data
    public static class CompositePart {
        private String sourceType;
        private Integer columnIndex;
        private List<String> headerAliases;
        private String value;
        private String cellRef;
        private Integer row;
        private Integer col;
    }
}
