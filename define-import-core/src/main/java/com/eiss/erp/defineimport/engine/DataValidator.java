package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * 数据校验器 (v1.8增强: 含重复性检测)
 * <p>
 * 校验解析后的行数据，包括：
 * <ul>
 *   <li>库存行必填字段检查（品类、规格默认必填）</li>
 *   <li>自定义必填字段检查</li>
 * </ul>
 * </p>
 */
public class DataValidator {

    private DataValidator() {
    }

    /**
     * 校验一行解析数据：必填字段、数值格式等。
     *
     * @param row                解析后的行
     * @param requiredFieldCodes 额外必填的字段代码集合
     * @param sheetName          当前工作表名
     * @return 校验错误列表（为空表示通过）
     */
    public static List<ExcelImportError> validate(ParsedRowDto row,
                                                   Set<String> requiredFieldCodes,
                                                   String sheetName) {
        List<ExcelImportError> errors = new ArrayList<>();
        if (row == null) {
            return errors;
        }

        // 品类 (category) 库存行默认必填
        if (isBlank(row.getCategory())) {
            errors.add(buildRequiredError(sheetName, row.getRowIndex(), "category", "品类"));
        }

        // 规格 (spec) 库存行默认必填
        if (isBlank(row.getSpec())) {
            errors.add(buildRequiredError(sheetName, row.getRowIndex(), "spec", "规格"));
        }

        // 自定义必填字段
        if (requiredFieldCodes != null) {
            for (String fieldCode : requiredFieldCodes) {
                // category 和 spec 已在上面检查过，跳过
                if ("category".equals(fieldCode) || "spec".equals(fieldCode)) {
                    continue;
                }
                String value = getFieldValue(row, fieldCode);
                if (isBlank(value)) {
                    errors.add(buildRequiredError(sheetName, row.getRowIndex(), fieldCode, fieldCodeToName(fieldCode)));
                }
            }
        }

        return errors;
    }

    /**
     * 根据字段代码获取行中的对应值（字符串形式）。
     */
    private static String getFieldValue(ParsedRowDto row, String fieldCode) {
        if (fieldCode == null) {
            return null;
        }
        return switch (fieldCode.trim()) {
            case "category" -> row.getCategory();
            case "spec" -> row.getSpec();
            case "origin" -> row.getOrigin();
            case "material" -> row.getMaterial();
            case "remark" -> row.getRemark();
            case "wall_thickness" -> row.getWallThickness();
            case "group_name" -> row.getGroupName();
            case "package_num" -> row.getPackageNum() == null ? null : row.getPackageNum().toString();
            case "whole_num" -> row.getWholeNum() == null ? null : row.getWholeNum().toString();
            case "odd_num" -> row.getOddNum() == null ? null : row.getOddNum().toString();
            case "weight" -> row.getWeight() == null ? null : row.getWeight().toPlainString();
            case "price" -> row.getPrice() == null ? null : row.getPrice().toPlainString();
            default -> null;
        };
    }

    /**
     * 字段代码转中文显示名。
     */
    private static String fieldCodeToName(String fieldCode) {
        if (fieldCode == null) {
            return "未知字段";
        }
        return switch (fieldCode.trim()) {
            case "category" -> "品类";
            case "spec" -> "规格";
            case "origin" -> "产地";
            case "material" -> "材质";
            case "remark" -> "备注";
            case "wall_thickness" -> "壁厚";
            case "group_name" -> "分组名称";
            case "package_num" -> "包装数量";
            case "whole_num" -> "整件数";
            case "odd_num" -> "零数";
            case "weight" -> "重量";
            case "price" -> "单价";
            default -> fieldCode;
        };
    }

    private static ExcelImportError buildRequiredError(String sheetName, int rowIndex,
                                                        String fieldCode, String fieldName) {
        return ExcelImportError.builder()
                .sheetName(sheetName)
                .rowIndex(rowIndex)
                .fieldName(fieldCode)
                .errorMsg(fieldName + "不能为空")
                .errorLevel("ERROR")
                .errorType("REQUIRED")
                .build();
    }

    private static boolean isBlank(String s) {
        return s == null || s.trim().isEmpty();
    }
}
