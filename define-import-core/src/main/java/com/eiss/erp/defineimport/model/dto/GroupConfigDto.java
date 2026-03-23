package com.eiss.erp.defineimport.model.dto;

import com.eiss.erp.defineimport.model.entity.ImportTemplateFieldValueMapping;
import lombok.Data;

import java.util.List;

/**
 * 数据分组配置DTO
 */
@Data
public class GroupConfigDto {
    private Long id;
    private Integer groupSeq;
    private String groupName;
    private String fixedCategory;
    private String fixedOrigin;
    private String fixedMaterial;
    private String fixedRemark;
    private Integer dataStartRow;
    private Integer dataEndRow;
    private List<FieldMappingDto> fields;
    private List<ImportTemplateFieldValueMapping> fieldValueMappings;
}
