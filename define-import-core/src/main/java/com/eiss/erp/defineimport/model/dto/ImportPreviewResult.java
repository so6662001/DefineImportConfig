package com.eiss.erp.defineimport.model.dto;

import lombok.Data;

import java.util.List;

/**
 * 导入预览完整结果。
 */
@Data
public class ImportPreviewResult {
    private String taskId;
    private String templateName;
    private List<ParsedRowDto> inventoryRows;
    private List<ParsedRowDto> priceRows;
    private List<ExcelImportError> errors;
    private ImportSummary summary;

    @Data
    public static class ImportSummary {
        private int totalSheets;
        private int inventorySheets;
        private int priceSheets;
        private int totalRows;
        private int successRows;
        private int errorRows;
        private int duplicateRows;
        private int priceMatchedRows;
        private int priceUnmatchedRows;
    }
}
