package com.eiss.erp.defineimport.model.config;

import java.util.List;

import lombok.Data;

/**
 * COLUMN 来源类型：数据列配置
 */
@Data
public class ColumnSourceConfig {
    private Integer columnIndex;
    private String headerName;
    private List<String> headerAliases;
    /** INDEX, ALIAS, INDEX_FIRST */
    private String matchMode;
}
