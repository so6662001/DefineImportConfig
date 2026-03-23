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
     * 预览导入（仅库存 Sheet，contentType=1）
     */
    ImportPreviewResult previewInventoryImport(InputStream fileStream, Long templateId);

    /**
     * 预览导入（仅价格 Sheet，contentType=2），并对已落库的库存明细执行价格回写
     */
    ImportPreviewResult previewPriceImport(InputStream fileStream, Long templateId,
                                           Long inventoryRecordId, String batchNo);

    /**
     * 确认导入：将解析后的数据保存到数据库
     *
     * @param templateId 模板 ID
     * @param rows       解析后的行数据
     * @param fileName   原始文件名
     * @param importType 导入类型
     * @param batchNo    批次号；空则自动生成 B+yyyyMMdd+序号
     */
    default Long confirmImport(Long templateId, List<ParsedRowDto> rows,
                               String fileName, int importType, String batchNo) {
        return confirmImport(templateId, rows, fileName, null, importType, batchNo);
    }

    /**
     * 确认导入（含文件路径）
     *
     * @param filePath 存储路径（可空，空则写入空串）
     */
    Long confirmImport(Long templateId, List<ParsedRowDto> rows,
                       String fileName, String filePath, int importType, String batchNo);
}
