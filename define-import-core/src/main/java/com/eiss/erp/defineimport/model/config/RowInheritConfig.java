package com.eiss.erp.defineimport.model.config;

import lombok.Data;

/**
 * 行继承配置（如合并单元格场景下规格继承）
 */
@Data
public class RowInheritConfig {
    private Boolean enabled;
    private String separator;
    private String partialPattern;
    /** PREFIX, SUFFIX */
    private String inheritPart;
    /** 如 "${prefix}*${current}" */
    private String assembleTemplate;
}
