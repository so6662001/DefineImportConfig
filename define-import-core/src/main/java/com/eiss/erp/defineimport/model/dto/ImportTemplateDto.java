package com.eiss.erp.defineimport.model.dto;

import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import lombok.Data;

import java.util.List;

/**
 * 导入模板完整配置DTO
 */
@Data
public class ImportTemplateDto {
    private Long id;
    private String templateCode;
    private String templateName;
    private Long supplierId;
    private String supplierName;
    private Integer allSheetsPrice;
    private List<SheetConfigDto> sheets;
    private List<CharTransformConfig.CharRule> templateCharRules;
}
