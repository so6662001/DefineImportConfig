package com.eiss.erp.defineimport.model.config;

import lombok.Data;

/**
 * FIXED_CELL 来源类型：固定单元格配置
 */
@Data
public class FixedCellSourceConfig {
    /** 如 "A1" */
    private String cellRef;
    private Integer row;
    private Integer col;
}
