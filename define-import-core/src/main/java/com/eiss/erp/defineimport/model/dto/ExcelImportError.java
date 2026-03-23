package com.eiss.erp.defineimport.model.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ExcelImportError {
    private String sheetName;
    /** 1-based for user display */
    private Integer rowIndex;
    private Integer colIndex;
    private String fieldName;
    private String errorMsg;
    private String rawValue;
    /** ERROR, WARNING */
    private String errorLevel;
    /** REQUIRED, NUMERIC, DUPLICATE, MATCH_FAIL */
    private String errorType;
    private Integer duplicateOfRow;
    private String duplicateOfSheet;
}
