package com.eiss.erp.defineimport.model.dto;

import lombok.Data;

/**
 * 字段映射配置DTO
 */
@Data
public class FieldMappingDto {
    private Long id;
    private String fieldCode;
    private String fieldName;
    private String sourceType;
    private String sourceConfig;
    private String transformConfig;
    private Integer required;
    private String defaultValue;
}
