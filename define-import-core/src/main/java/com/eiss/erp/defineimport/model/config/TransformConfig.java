package com.eiss.erp.defineimport.model.config;

import java.util.List;

import lombok.Data;

/**
 * 数据转换配置
 */
@Data
public class TransformConfig {
    private Boolean trimWhitespace;
    private Boolean removeUnit;
    private List<String> unitPatterns;
    private Integer numericPrecision;
    private String rangeDelimiter;
    private Boolean parseAsRange;
    private String regexExtract;
    private Boolean extractNumber;
    private CharTransformConfig charTransform;
    private RowInheritConfig rowInherit;
}
