package com.eiss.erp.defineimport.model.config;

import java.util.List;

import lombok.Data;

/**
 * 规格区间解析配置
 */
@Data
public class SpecRangeConfig {
    private String rangePattern;
    private List<String> rangeSeparators;
    private List<String> infinityKeywords;
    private List<String> zeroKeywords;
    /** PRICE_CONTAINS_INVENTORY, RANGE_OVERLAP 等 */
    private String matchStrategy;
}
