package com.eiss.erp.defineimport.model.dto;

import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import lombok.Data;

import java.util.List;

/**
 * 价格匹配规则DTO
 */
@Data
public class PriceMatchRuleDto {
    private List<String> matchFields;
    private Integer wallThicknessMatchMode;
    private Integer specRangeMatchMode;
    private SpecRangeConfig specRangeConfig;
}
