package com.eiss.erp.defineimport.model.dto;

import lombok.Data;

import java.util.List;

/**
 * Sheet配置DTO
 */
@Data
public class SheetConfigDto {
    private Long id;
    private Integer sheetIndex;
    private String sheetName;
    private Integer contentType;
    private Integer headerRowIndex;
    private Integer dataStartRowIndex;
    private Integer dataEndRowIndex;
    private Integer emptyRowThreshold;
    private Integer enableMergeCell;
    private Integer sortOrder;
    private List<GroupConfigDto> groups;
    private PriceMatchRuleDto priceMatchRule;
}
