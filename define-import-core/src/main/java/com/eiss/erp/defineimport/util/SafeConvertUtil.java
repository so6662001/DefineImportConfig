package com.eiss.erp.defineimport.util;

import com.eiss.erp.defineimport.model.dto.ExcelImportError;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 安全类型转换：失败不抛异常，将错误写入 {@link ExcelImportError} 列表。
 */
public final class SafeConvertUtil {

    private static final Pattern FIRST_NUMBER = Pattern.compile("[-+]?\\d+(?:\\.\\d+)?");

    private SafeConvertUtil() {
    }

    /**
     * 从字符串中提取首个数字子串（如 "127支/件" → "127"，"约5吨" → "5"）。
     *
     * @return 数字字符串，无法提取时返回 {@code null}
     */
    public static String extractNumber(String value) {
        if (value == null) {
            return null;
        }
        Matcher m = FIRST_NUMBER.matcher(value.trim());
        return m.find() ? m.group() : null;
    }

    /**
     * 解析为 {@link BigDecimal}；混合文本会先 {@link #extractNumber(String)}。
     * 转换失败时记录错误并返回 {@code null}。
     */
    public static BigDecimal toBigDecimal(
            String value,
            String fieldName,
            int rowIndex,
            String sheetName,
            List<ExcelImportError> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String numStr = extractNumber(value.trim());
        if (numStr == null) {
            addNumericError(value, fieldName, rowIndex, sheetName, errors, "无法从单元格中解析出数字");
            return null;
        }
        try {
            return new BigDecimal(numStr);
        } catch (NumberFormatException ex) {
            addNumericError(value, fieldName, rowIndex, sheetName, errors, "数字格式无效: " + ex.getMessage());
            return null;
        }
    }

    /**
     * 解析为 {@link Integer}；混合文本会先 {@link #extractNumber(String)}。
     * 若提取到小数，按 {@link RoundingMode#HALF_UP} 取整。
     */
    public static Integer toInteger(
            String value,
            String fieldName,
            int rowIndex,
            String sheetName,
            List<ExcelImportError> errors) {
        if (value == null || value.isBlank()) {
            return null;
        }
        BigDecimal bd = toBigDecimal(value, fieldName, rowIndex, sheetName, errors);
        if (bd == null) {
            return null;
        }
        try {
            return bd.setScale(0, RoundingMode.HALF_UP).intValueExact();
        } catch (ArithmeticException ex) {
            addNumericError(value, fieldName, rowIndex, sheetName, errors, "整数转换失败: " + ex.getMessage());
            return null;
        }
    }

    private static void addNumericError(
            String rawValue,
            String fieldName,
            int rowIndex,
            String sheetName,
            List<ExcelImportError> errors,
            String msg) {
        if (errors == null) {
            return;
        }
        errors.add(ExcelImportError.builder()
                .sheetName(sheetName)
                .rowIndex(rowIndex)
                .fieldName(fieldName)
                .rawValue(rawValue)
                .errorMsg(msg)
                .errorLevel("ERROR")
                .errorType("NUMERIC")
                .build());
    }
}
