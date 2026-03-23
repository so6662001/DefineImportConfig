package com.eiss.erp.defineimport.engine;

import com.eiss.erp.defineimport.model.config.*;
import com.eiss.erp.defineimport.model.enums.SourceTypeEnum;
import com.eiss.erp.defineimport.util.CellRefUtil;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * 字段值解析器
 * <p>
 * 根据 source_type 和 source_config 从 Excel 数据中提取字段的原始值。
 * 支持五种来源类型：
 * <ul>
 *   <li>COLUMN - 数据列：从当前行指定列取值</li>
 *   <li>FIXED_CELL - 固定单元格：从表头区域某个固定位置取值（如供应商名称）</li>
 *   <li>FIXED_VALUE - 固定值：直接返回配置中的常量</li>
 *   <li>COMPOSITE - 多源组合：将多个子来源的值拼接在一起</li>
 *   <li>COLUMN_HEADER - 列表头：取某列的表头文本作为值</li>
 * </ul>
 * </p>
 */
public class FieldValueResolver {

    private static final Logger log = LoggerFactory.getLogger(FieldValueResolver.class);

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private FieldValueResolver() {
    }

    /**
     * 根据来源类型和配置从 Excel 行数据中解析字段值。
     *
     * @param sourceType         来源类型代码（COLUMN / FIXED_CELL / FIXED_VALUE / COMPOSITE / COLUMN_HEADER）
     * @param sourceConfigJson   来源配置 JSON 字符串
     * @param rowData            当前行数据（列索引 → 单元格文本）
     * @param headerColumnMap    已匹配的表头映射（fieldCode → 实际列索引）
     * @param fieldCode          当前字段代码
     * @param mergeCellCollector 合并单元格收集器
     * @param currentRowIndex    当前行索引（0-based）
     * @param headRowData        表头行数据（列索引 → 表头文本）
     * @return 解析出的字符串值，无法解析时返回 null
     */
    public static String resolve(String sourceType, String sourceConfigJson,
                                  Map<Integer, String> rowData,
                                  Map<String, Integer> headerColumnMap,
                                  String fieldCode,
                                  MergeCellCollector mergeCellCollector,
                                  int currentRowIndex,
                                  Map<Integer, String> headRowData) {
        if (sourceType == null || sourceType.isBlank()) {
            return null;
        }

        SourceTypeEnum type;
        try {
            type = SourceTypeEnum.fromCode(sourceType.trim());
        } catch (IllegalArgumentException e) {
            log.warn("未知的来源类型: {}, fieldCode={}", sourceType, fieldCode);
            return null;
        }

        switch (type) {
            case COLUMN:
                return resolveColumn(sourceConfigJson, rowData, headerColumnMap,
                        fieldCode, mergeCellCollector, currentRowIndex);
            case FIXED_CELL:
                return resolveFixedCell(sourceConfigJson, mergeCellCollector);
            case FIXED_VALUE:
                return resolveFixedValue(sourceConfigJson);
            case COMPOSITE:
                return resolveComposite(sourceConfigJson, rowData, headerColumnMap,
                        fieldCode, mergeCellCollector, currentRowIndex, headRowData);
            case COLUMN_HEADER:
                return resolveColumnHeader(sourceConfigJson, headRowData);
            default:
                return null;
        }
    }

    /**
     * COLUMN 类型：从当前行的指定列取值。
     * <p>
     * 列索引优先从 headerColumnMap（通过 HeaderMatcher 匹配得到）获取，
     * 若不存在则从配置中的 columnIndex 获取。
     * 若值为空，尝试从合并单元格中获取。
     * </p>
     */
    private static String resolveColumn(String sourceConfigJson,
                                         Map<Integer, String> rowData,
                                         Map<String, Integer> headerColumnMap,
                                         String fieldCode,
                                         MergeCellCollector mergeCellCollector,
                                         int currentRowIndex) {
        // 优先从表头匹配结果取列索引
        Integer colIndex = null;
        if (headerColumnMap != null) {
            colIndex = headerColumnMap.get(fieldCode);
        }

        // 回退到配置中的 columnIndex
        if (colIndex == null && sourceConfigJson != null && !sourceConfigJson.isBlank()) {
            ColumnSourceConfig config = parseJson(sourceConfigJson, ColumnSourceConfig.class);
            if (config != null) {
                colIndex = config.getColumnIndex();
            }
        }

        if (colIndex == null) {
            return null;
        }

        // 从当前行取值
        String value = null;
        if (rowData != null) {
            value = rowData.get(colIndex);
        }

        // 值为空时尝试合并单元格
        if (isBlank(value) && mergeCellCollector != null) {
            String mergedValue = mergeCellCollector.getMergedValue(currentRowIndex, colIndex);
            if (mergedValue != null) {
                value = mergedValue;
            }
        }

        return value;
    }

    /**
     * FIXED_CELL 类型：从固定单元格取值。
     * <p>
     * 支持两种坐标指定方式：
     * 1. cellRef 格式（如 "A1"），通过 CellRefUtil 解析为行列索引
     * 2. 直接指定 row + col
     * 值从 mergeCellCollector 的 cellValueMap 中获取（第一遍读取时已记录）。
     * </p>
     */
    private static String resolveFixedCell(String sourceConfigJson,
                                            MergeCellCollector mergeCellCollector) {
        if (sourceConfigJson == null || sourceConfigJson.isBlank()) {
            return null;
        }
        FixedCellSourceConfig config = parseJson(sourceConfigJson, FixedCellSourceConfig.class);
        if (config == null) {
            return null;
        }

        int row;
        int col;
        if (config.getCellRef() != null && !config.getCellRef().isBlank()) {
            row = CellRefUtil.getRowIndex(config.getCellRef());
            col = CellRefUtil.getColIndex(config.getCellRef());
        } else if (config.getRow() != null && config.getCol() != null) {
            row = config.getRow();
            col = config.getCol();
        } else {
            return null;
        }

        if (mergeCellCollector == null) {
            return null;
        }

        // 先尝试合并区域值
        String mergedValue = mergeCellCollector.getMergedValue(row, col);
        if (mergedValue != null) {
            return mergedValue;
        }
        // 再尝试直接从 cellValueMap 获取
        return mergeCellCollector.getCellValueMap().get(MergeCellCollector.buildKey(row, col));
    }

    /**
     * FIXED_VALUE 类型：直接返回配置中的固定值
     */
    private static String resolveFixedValue(String sourceConfigJson) {
        if (sourceConfigJson == null || sourceConfigJson.isBlank()) {
            return null;
        }
        FixedValueSourceConfig config = parseJson(sourceConfigJson, FixedValueSourceConfig.class);
        if (config == null) {
            return null;
        }
        return config.getValue();
    }

    /**
     * COMPOSITE 类型：多源组合。
     * <p>
     * 遍历各子部分(parts)，根据每个part的sourceType解析出值，
     * 然后按 template 或 separator 拼接。
     * template 格式如 "${0}${1}${2}"，其中数字是 parts 的索引。
     * </p>
     */
    private static String resolveComposite(String sourceConfigJson,
                                            Map<Integer, String> rowData,
                                            Map<String, Integer> headerColumnMap,
                                            String fieldCode,
                                            MergeCellCollector mergeCellCollector,
                                            int currentRowIndex,
                                            Map<Integer, String> headRowData) {
        if (sourceConfigJson == null || sourceConfigJson.isBlank()) {
            return null;
        }
        CompositeSourceConfig config = parseJson(sourceConfigJson, CompositeSourceConfig.class);
        if (config == null || config.getParts() == null || config.getParts().isEmpty()) {
            return null;
        }

        List<CompositeSourceConfig.CompositePart> parts = config.getParts();
        String[] partValues = new String[parts.size()];

        for (int i = 0; i < parts.size(); i++) {
            CompositeSourceConfig.CompositePart part = parts.get(i);
            partValues[i] = resolveCompositePart(part, rowData, headerColumnMap,
                    fieldCode, mergeCellCollector, currentRowIndex, headRowData);
            if (partValues[i] == null) {
                partValues[i] = "";
            }
        }

        // 使用 template 拼接
        if (config.getTemplate() != null && !config.getTemplate().isBlank()) {
            String result = config.getTemplate();
            for (int i = 0; i < partValues.length; i++) {
                result = result.replace("${" + i + "}", partValues[i]);
            }
            return result;
        }

        // 使用 separator 拼接
        String sep = config.getSeparator() != null ? config.getSeparator() : "";
        return String.join(sep, partValues);
    }

    /**
     * 解析组合来源的单个子部分
     */
    private static String resolveCompositePart(CompositeSourceConfig.CompositePart part,
                                                Map<Integer, String> rowData,
                                                Map<String, Integer> headerColumnMap,
                                                String fieldCode,
                                                MergeCellCollector mergeCellCollector,
                                                int currentRowIndex,
                                                Map<Integer, String> headRowData) {
        if (part == null || part.getSourceType() == null) {
            return null;
        }

        String partType = part.getSourceType().trim();

        switch (partType) {
            case "COLUMN": {
                Integer colIndex = part.getColumnIndex();
                // 也尝试通过别名匹配
                if (colIndex == null && part.getHeaderAliases() != null
                        && !part.getHeaderAliases().isEmpty() && headRowData != null) {
                    colIndex = matchAlias(part.getHeaderAliases(), headRowData);
                }
                if (colIndex == null) {
                    return null;
                }
                String value = rowData != null ? rowData.get(colIndex) : null;
                if (isBlank(value) && mergeCellCollector != null) {
                    String merged = mergeCellCollector.getMergedValue(currentRowIndex, colIndex);
                    if (merged != null) {
                        value = merged;
                    }
                }
                return value;
            }
            case "FIXED_VALUE": {
                return part.getValue();
            }
            case "FIXED_CELL": {
                int row, col;
                if (part.getCellRef() != null && !part.getCellRef().isBlank()) {
                    row = CellRefUtil.getRowIndex(part.getCellRef());
                    col = CellRefUtil.getColIndex(part.getCellRef());
                } else if (part.getRow() != null && part.getCol() != null) {
                    row = part.getRow();
                    col = part.getCol();
                } else {
                    return null;
                }
                if (mergeCellCollector == null) {
                    return null;
                }
                String mergedVal = mergeCellCollector.getMergedValue(row, col);
                if (mergedVal != null) {
                    return mergedVal;
                }
                return mergeCellCollector.getCellValueMap().get(MergeCellCollector.buildKey(row, col));
            }
            case "COLUMN_HEADER": {
                Integer colIndex = part.getColumnIndex();
                if (colIndex != null && headRowData != null) {
                    return headRowData.get(colIndex);
                }
                return null;
            }
            case "COMPOSITE": {
                if (part.getSourceConfig() == null || part.getSourceConfig().isBlank()) {
                    return null;
                }
                return resolveComposite(part.getSourceConfig(), rowData, headerColumnMap,
                        fieldCode, mergeCellCollector, currentRowIndex, headRowData);
            }
            default:
                return null;
        }
    }

    /**
     * 从表头行数据中按别名匹配找到列索引
     */
    private static Integer matchAlias(List<String> aliases, Map<Integer, String> headRowData) {
        if (aliases == null || headRowData == null) {
            return null;
        }
        for (String alias : aliases) {
            if (alias == null || alias.isBlank()) {
                continue;
            }
            String normalizedAlias = HeaderMatcher.normalize(alias);
            for (Map.Entry<Integer, String> entry : headRowData.entrySet()) {
                String normalizedHead = HeaderMatcher.normalize(entry.getValue());
                if (normalizedAlias.equals(normalizedHead)) {
                    return entry.getKey();
                }
            }
        }
        return null;
    }

    /**
     * COLUMN_HEADER 类型：获取指定列的表头文本作为字段值
     */
    private static String resolveColumnHeader(String sourceConfigJson,
                                               Map<Integer, String> headRowData) {
        if (sourceConfigJson == null || sourceConfigJson.isBlank()) {
            return null;
        }
        ColumnHeaderSourceConfig config = parseJson(sourceConfigJson, ColumnHeaderSourceConfig.class);
        if (config == null || config.getColumnIndex() == null) {
            return null;
        }
        if (headRowData == null) {
            return null;
        }
        return headRowData.get(config.getColumnIndex());
    }

    /**
     * 安全地将 JSON 字符串解析为目标类型
     */
    private static <T> T parseJson(String json, Class<T> clazz) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, clazz);
        } catch (JsonProcessingException e) {
            log.warn("JSON解析失败, targetClass={}, json={}: {}",
                    clazz.getSimpleName(), json, e.getMessage());
            return null;
        }
    }

    private static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }
}
