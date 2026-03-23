package com.eiss.erp.defineimport.model.dto;

import com.eiss.erp.defineimport.util.SafeConvertUtil;
import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

/**
 * 解析后的一行业务数据。
 */
@Data
public class ParsedRowDto {
    /** 与 {@link ExcelImportError#getRowIndex()} 一致，建议为 Excel 行号（1-based） */
    private int rowIndex;
    private String sheetName;
    private String groupName;
    private String category;
    private String spec;
    private String origin;
    private String material;
    private Integer packageNum;
    private Integer wholeNum;
    private Integer oddNum;
    private BigDecimal weight;
    private BigDecimal price;
    private String wallThickness;
    private String remark;

    /**
     * 按字段代码写入属性；数值字段通过 {@link SafeConvertUtil}，失败时写入 {@code errors}。
     */
    public void setFieldValue(String fieldCode, String value, List<ExcelImportError> errors) {
        if (fieldCode == null || fieldCode.isBlank()) {
            return;
        }
        String code = fieldCode.trim();
        switch (code) {
            case "group_name" -> this.groupName = trimToNull(value);
            case "category" -> this.category = trimToNull(value);
            case "spec" -> this.spec = trimToNull(value);
            case "origin" -> this.origin = trimToNull(value);
            case "material" -> this.material = trimToNull(value);
            case "remark" -> this.remark = trimToNull(value);
            case "wall_thickness" -> this.wallThickness = trimToNull(value);
            case "package_num" ->
                    this.packageNum = SafeConvertUtil.toInteger(value, code, rowIndex, sheetName, errors);
            case "whole_num" ->
                    this.wholeNum = SafeConvertUtil.toInteger(value, code, rowIndex, sheetName, errors);
            case "odd_num" ->
                    this.oddNum = SafeConvertUtil.toInteger(value, code, rowIndex, sheetName, errors);
            case "weight" ->
                    this.weight = SafeConvertUtil.toBigDecimal(value, code, rowIndex, sheetName, errors);
            case "price" ->
                    this.price = SafeConvertUtil.toBigDecimal(value, code, rowIndex, sheetName, errors);
            default -> addUnknownFieldError(fieldCode, value, errors);
        }
    }

    /** 用于重复行检测的业务键 */
    public String buildUniqueKey() {
        return String.join("|",
                nullToEmpty(category),
                nullToEmpty(spec),
                nullToEmpty(origin),
                nullToEmpty(material),
                nullToEmpty(remark));
    }

    private static String nullToEmpty(String s) {
        return s == null ? "" : s.trim();
    }

    private static String trimToNull(String s) {
        if (s == null) {
            return null;
        }
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private void addUnknownFieldError(String fieldCode, String value, List<ExcelImportError> errors) {
        if (errors == null) {
            return;
        }
        errors.add(ExcelImportError.builder()
                .sheetName(sheetName)
                .rowIndex(rowIndex)
                .fieldName(fieldCode)
                .rawValue(value)
                .errorMsg("未知字段代码: " + fieldCode)
                .errorLevel("WARNING")
                .errorType("MATCH_FAIL")
                .build());
    }
}
