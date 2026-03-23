package com.eiss.erp.defineimport.service;

import com.eiss.erp.defineimport.model.dto.ImportPreviewResult;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;

import java.io.InputStream;
import java.util.List;

/**
 * 动态Excel导入服务接口
 */
public interface DynamicExcelImportService {

    /**
     * 预览导入：使用模板解析Excel，返回预览结果
     */
    ImportPreviewResult previewImport(InputStream fileStream, Long templateId);

    /**
     * 确认导入：将解析后的数据保存到数据库
     */
    Long confirmImport(String taskId, List<ParsedRowDto> rows);
}
