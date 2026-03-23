package com.eiss.erp.defineimport.engine;

import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.event.AnalysisEventListener;
import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import com.eiss.erp.defineimport.model.config.ColumnHeaderSourceConfig;
import com.eiss.erp.defineimport.model.config.TransformConfig;
import com.eiss.erp.defineimport.model.dto.*;
import com.eiss.erp.defineimport.model.enums.ContentTypeEnum;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.*;

/**
 * 动态Excel监听器
 * EasyExcel逐行回调，基于模板规则解析数据
 */
public class DynamicExcelListener extends AnalysisEventListener<Map<Integer, String>> {

    private static final Logger log = LoggerFactory.getLogger(DynamicExcelListener.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final SheetConfigDto sheetConfig;
    private final MergeCellCollector mergeCellCollector;
    private final Map<Integer, String> headRowData;

    private Map<String, Integer> headerColumnMap;
    private final List<ParsedRowDto> parsedRows = new ArrayList<>();
    private final List<ExcelImportError> errors = new ArrayList<>();
    private final DuplicateDetector duplicateDetector;
    private final RowInheritResolver rowInheritResolver = new RowInheritResolver();
    private final Map<String, List<CharTransformConfig.CharRule>> charPipelineCache;
    private int consecutiveEmptyRows = 0;
    private boolean stopped = false;

    public DynamicExcelListener(SheetConfigDto sheetConfig,
                                MergeCellCollector mergeCellCollector,
                                Map<Integer, String> headRowData,
                                Map<String, Integer> headerColumnMap,
                                Map<String, List<CharTransformConfig.CharRule>> charPipelineCache,
                                DuplicateDetector duplicateDetector) {
        this.sheetConfig = sheetConfig;
        this.mergeCellCollector = mergeCellCollector;
        this.headRowData = headRowData != null ? headRowData : Collections.emptyMap();
        this.headerColumnMap = headerColumnMap != null ? headerColumnMap : Collections.emptyMap();
        this.charPipelineCache = charPipelineCache != null ? charPipelineCache : Collections.emptyMap();
        this.duplicateDetector = duplicateDetector != null ? duplicateDetector : new DuplicateDetector();
    }

    @Override
    public void invokeHeadMap(Map<Integer, String> headMap, AnalysisContext context) {
        // Header is pre-matched; no additional action needed during streaming pass
    }

    @Override
    public void invoke(Map<Integer, String> rowData, AnalysisContext context) {
        if (stopped) {
            return;
        }

        int currentRow = context.readRowHolder().getRowIndex();

        Integer dataStart = sheetConfig.getDataStartRowIndex();
        if (dataStart != null && currentRow < dataStart) {
            return;
        }

        Integer dataEnd = sheetConfig.getDataEndRowIndex();
        if (dataEnd != null && currentRow > dataEnd) {
            stopped = true;
            return;
        }

        if (isEmptyRow(rowData)) {
            consecutiveEmptyRows++;
            int threshold = sheetConfig.getEmptyRowThreshold() != null
                    ? sheetConfig.getEmptyRowThreshold() : 2;
            if (consecutiveEmptyRows >= threshold) {
                stopped = true;
            }
            return;
        }
        consecutiveEmptyRows = 0;

        List<GroupConfigDto> groups = sheetConfig.getGroups();
        if (groups == null || groups.isEmpty()) {
            return;
        }

        for (GroupConfigDto group : groups) {
            if (!isRowInGroup(currentRow, group)) {
                continue;
            }
            processGroupRow(rowData, currentRow, group);
        }
    }

    @Override
    public void doAfterAllAnalysed(AnalysisContext context) {
        log.debug("Sheet [{}] parsing completed. Rows: {}, Errors: {}",
                sheetConfig.getSheetName(), parsedRows.size(), errors.size());
    }

    private boolean isRowInGroup(int currentRow, GroupConfigDto group) {
        Integer groupStart = group.getDataStartRow();
        Integer groupEnd = group.getDataEndRow();
        if (groupStart != null && currentRow < groupStart) {
            return false;
        }
        if (groupEnd != null && currentRow > groupEnd) {
            return false;
        }
        return true;
    }

    private void processGroupRow(Map<Integer, String> rowData, int currentRow, GroupConfigDto group) {
        String sheetName = sheetConfig.getSheetName() != null
                ? sheetConfig.getSheetName() : "Sheet" + sheetConfig.getSheetIndex();

        String mappingQualifier = resolveQualifier(group);

        ParsedRowDto row = new ParsedRowDto();
        row.setRowIndex(currentRow + 1);
        row.setSheetName(sheetName);
        row.setGroupName(group.getGroupName());

        List<FieldMappingDto> fields = group.getFields();
        if (fields == null || fields.isEmpty()) {
            return;
        }

        Set<String> requiredCodes = new HashSet<>();
        for (FieldMappingDto field : fields) {
            String rawValue = FieldValueResolver.resolve(
                    field.getSourceType(),
                    field.getSourceConfig(),
                    rowData,
                    headerColumnMap,
                    field.getFieldCode(),
                    mergeCellCollector,
                    currentRow,
                    headRowData);

            if (rawValue == null && field.getDefaultValue() != null) {
                rawValue = field.getDefaultValue();
            }

            List<CharTransformConfig.CharRule> pipeline =
                    charPipelineCache.get(field.getFieldCode());

            String transformedValue = DataTransformer.transform(
                    rawValue, field.getTransformConfig(), pipeline);

            TransformConfig tc = parseTransformConfig(field.getTransformConfig(), field.getFieldCode());
            if (tc != null && tc.getRowInherit() != null) {
                transformedValue = rowInheritResolver.resolve(
                        field.getFieldCode(), transformedValue, tc.getRowInherit());
            }

            if (group.getFieldValueMappings() != null
                    && !group.getFieldValueMappings().isEmpty()) {
                transformedValue = FieldValueMapper.map(
                        field.getFieldCode(),
                        transformedValue,
                        mappingQualifier,
                        group.getFieldValueMappings());
            }

            row.setFieldValue(field.getFieldCode(), transformedValue, errors);

            if (field.getRequired() != null && field.getRequired() == 1) {
                requiredCodes.add(field.getFieldCode());
            }
        }

        applyFixedGroupValues(row, group);

        boolean isInventory = sheetConfig.getContentType() != null
                && sheetConfig.getContentType() == ContentTypeEnum.INVENTORY.getCode();

        if (isInventory) {
            List<ExcelImportError> validationErrors = DataValidator.validate(row, requiredCodes, sheetName);
            errors.addAll(validationErrors);

            ExcelImportError dupError = duplicateDetector.check(row);
            if (dupError != null) {
                errors.add(dupError);
                return;
            }
        }

        parsedRows.add(row);
    }

    /**
     * Resolves qualifier for field value mappings: header text from COLUMN_HEADER fields,
     * otherwise the group name (e.g. semantic labels like 黑材 / 白材).
     */
    private String resolveQualifier(GroupConfigDto group) {
        if (group.getFields() != null) {
            for (FieldMappingDto field : group.getFields()) {
                if (!"COLUMN_HEADER".equals(field.getSourceType())) {
                    continue;
                }
                if (field.getSourceConfig() == null || field.getSourceConfig().isBlank()) {
                    continue;
                }
                try {
                    ColumnHeaderSourceConfig config = OBJECT_MAPPER.readValue(
                            field.getSourceConfig(), ColumnHeaderSourceConfig.class);
                    if (config != null && config.getColumnIndex() != null) {
                        String headerText = headRowData.get(config.getColumnIndex());
                        if (headerText != null && !headerText.isBlank()) {
                            return headerText.trim();
                        }
                    }
                } catch (Exception e) {
                    log.warn("解析 COLUMN_HEADER 的 sourceConfig 失败, fieldCode={}: {}",
                            field.getFieldCode(), e.getMessage());
                }
            }
        }
        String name = group.getGroupName();
        return name != null && !name.isBlank() ? name.trim() : null;
    }

    private void applyFixedGroupValues(ParsedRowDto row, GroupConfigDto group) {
        if (group.getFixedCategory() != null && row.getCategory() == null) {
            row.setCategory(group.getFixedCategory());
        }
        if (group.getFixedOrigin() != null && row.getOrigin() == null) {
            row.setOrigin(group.getFixedOrigin());
        }
        if (group.getFixedMaterial() != null && row.getMaterial() == null) {
            row.setMaterial(group.getFixedMaterial());
        }
        if (group.getFixedRemark() != null && row.getRemark() == null) {
            row.setRemark(group.getFixedRemark());
        }
    }

    private boolean isEmptyRow(Map<Integer, String> rowData) {
        if (rowData == null || rowData.isEmpty()) {
            return true;
        }
        for (String val : rowData.values()) {
            if (val != null && !val.trim().isEmpty()) {
                return false;
            }
        }
        return true;
    }

    private TransformConfig parseTransformConfig(String json, String fieldCode) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return OBJECT_MAPPER.readValue(json, TransformConfig.class);
        } catch (Exception e) {
            log.warn("解析 TransformConfig JSON 失败, fieldCode={}: {}", fieldCode, e.getMessage());
            return null;
        }
    }

    public List<ParsedRowDto> getParsedRows() {
        return parsedRows;
    }

    public List<ExcelImportError> getErrors() {
        return errors;
    }
}
