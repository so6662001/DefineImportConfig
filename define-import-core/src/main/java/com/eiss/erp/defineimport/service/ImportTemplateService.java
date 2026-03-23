package com.eiss.erp.defineimport.service;

import com.eiss.erp.defineimport.model.dto.ImportTemplateDto;
import com.eiss.erp.defineimport.model.entity.ImportTemplate;

import java.util.List;

/**
 * 导入模板服务接口
 */
public interface ImportTemplateService {

    /**
     * 保存完整模板（含所有子配置）
     */
    Long saveTemplate(ImportTemplateDto dto);

    /**
     * 更新模板
     */
    void updateTemplate(ImportTemplateDto dto);

    /**
     * 删除模板（逻辑删除）
     */
    void deleteTemplate(Long id);

    /**
     * 根据ID获取完整模板配置
     */
    ImportTemplateDto getTemplateById(Long id);

    /**
     * 列表查询模板
     */
    List<ImportTemplate> listTemplates(Long supplierId, String keyword);
}
