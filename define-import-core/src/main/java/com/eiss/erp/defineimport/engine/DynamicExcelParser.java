package com.eiss.erp.defineimport.engine;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.context.AnalysisContext;
import com.alibaba.excel.enums.CellExtraTypeEnum;
import com.alibaba.excel.event.AnalysisEventListener;
import com.alibaba.excel.metadata.CellExtra;
import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import com.eiss.erp.defineimport.model.config.ColumnSourceConfig;
import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import com.eiss.erp.defineimport.model.dto.*;
import com.eiss.erp.defineimport.model.enums.ContentTypeEnum;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.*;
import java.util.stream.Collectors;

/**
 * 动态Excel解析器 - 总入口
 * 协调整个Excel的解析流程:
 * 1. 加载模板配置
 * 2. 第一遍读取: 收集合并单元格信息
 * 3. 第二遍读取: 流式逐行解析
 * 4. 根据importType分流处理
 */
public class DynamicExcelParser {

    private static final Logger log = LoggerFactory.getLogger(DynamicExcelParser.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    /**
     * Parse an Excel file using the given template configuration.
     *
     * @param inputStream Excel file input stream
     * @param templateDto complete template config (sheets, groups, fields, mappings, etc.)
     * @return ImportPreviewResult with parsed rows and errors
     */
    public ImportPreviewResult parse(InputStream inputStream, ImportTemplateDto templateDto) {
        byte[] fileBytes = toByteArray(inputStream);

        List<SheetConfigDto> sheets = templateDto.getSheets();
        if (sheets == null || sheets.isEmpty()) {
            return buildEmptyResult(templateDto.getTemplateName());
        }

        sheets.sort(Comparator.comparingInt(s -> s.getSortOrder() != null ? s.getSortOrder() : Integer.MAX_VALUE));

        List<ParsedRowDto> allInventoryRows = new ArrayList<>();
        List<ParsedRowDto> allPriceRows = new ArrayList<>();
        List<ExcelImportError> allErrors = new ArrayList<>();
        DuplicateDetector duplicateDetector = new DuplicateDetector();

        for (SheetConfigDto sheetConfig : sheets) {
            int sheetIdx = sheetConfig.getSheetIndex() != null ? sheetConfig.getSheetIndex() : 0;

            // --- First pass: collect merge cells and all cell values ---
            MergeCellCollector mergeCellCollector = new MergeCellCollector();
            Map<Integer, String> headerRowData = new HashMap<>();
            firstPass(fileBytes, sheetIdx, sheetConfig, mergeCellCollector, headerRowData);

            // --- Build header column mapping ---
            Map<String, ColumnSourceConfig> fieldColumnConfigs = extractColumnSourceConfigs(sheetConfig);
            Map<String, Integer> headerColumnMap = HeaderMatcher.match(headerRowData, fieldColumnConfigs);

            // --- Build char transform pipelines ---
            Map<String, List<CharTransformConfig.CharRule>> charPipelineCache =
                    buildCharPipelines(sheetConfig, templateDto.getTemplateCharRules());

            // --- Second pass: stream parse data rows ---
            DynamicExcelListener listener = new DynamicExcelListener(
                    sheetConfig, mergeCellCollector, headerRowData,
                    headerColumnMap, charPipelineCache, duplicateDetector);

            EasyExcel.read(new ByteArrayInputStream(fileBytes), listener)
                    .sheet(sheetIdx)
                    .headRowNumber(0)
                    .doRead();

            // Separate by content type
            boolean isInventory = sheetConfig.getContentType() != null
                    && sheetConfig.getContentType() == ContentTypeEnum.INVENTORY.getCode();
            if (isInventory) {
                allInventoryRows.addAll(listener.getParsedRows());
            } else {
                allPriceRows.addAll(listener.getParsedRows());
            }
            allErrors.addAll(listener.getErrors());
        }

        // Price matching: fill prices into inventory rows
        if (!allInventoryRows.isEmpty() && !allPriceRows.isEmpty()) {
            PriceMatchRuleDto priceRule = findPriceMatchRule(sheets);
            if (priceRule != null) {
                List<String> matchFields = priceRule.getMatchFields() != null
                        ? priceRule.getMatchFields()
                        : Arrays.asList("category", "spec", "origin");
                int wtMode = priceRule.getWallThicknessMatchMode() != null
                        ? priceRule.getWallThicknessMatchMode() : 0;
                int srMode = priceRule.getSpecRangeMatchMode() != null
                        ? priceRule.getSpecRangeMatchMode() : 0;
                SpecRangeConfig srConfig = priceRule.getSpecRangeConfig();

                List<ExcelImportError> matchErrors = PriceMatcher.match(
                        allInventoryRows, allPriceRows,
                        matchFields, wtMode, srMode, srConfig, "*");
                allErrors.addAll(matchErrors);
            }
        }

        return buildResult(templateDto.getTemplateName(), sheets,
                allInventoryRows, allPriceRows, allErrors, duplicateDetector);
    }

    /**
     * First pass: read all cells and merge region info.
     */
    private void firstPass(byte[] fileBytes, int sheetIdx, SheetConfigDto sheetConfig,
                           MergeCellCollector mergeCellCollector,
                           Map<Integer, String> headerRowData) {
        boolean mergeCellEnabled = sheetConfig.getEnableMergeCell() != null
                && sheetConfig.getEnableMergeCell() == 1;

        var builder = EasyExcel.read(new ByteArrayInputStream(fileBytes),
                new FirstPassListener(mergeCellCollector, headerRowData,
                        sheetConfig.getHeaderRowIndex()));

        if (mergeCellEnabled) {
            builder.extraRead(CellExtraTypeEnum.MERGE);
        }

        builder.sheet(sheetIdx).headRowNumber(0).doRead();
    }

    /**
     * Extract ColumnSourceConfig from all COLUMN-type fields across groups.
     */
    private Map<String, ColumnSourceConfig> extractColumnSourceConfigs(SheetConfigDto sheetConfig) {
        Map<String, ColumnSourceConfig> result = new HashMap<>();
        if (sheetConfig.getGroups() == null) {
            return result;
        }
        for (GroupConfigDto group : sheetConfig.getGroups()) {
            if (group.getFields() == null) {
                continue;
            }
            for (FieldMappingDto field : group.getFields()) {
                if (!"COLUMN".equals(field.getSourceType())) {
                    continue;
                }
                if (field.getSourceConfig() == null || field.getSourceConfig().isBlank()) {
                    continue;
                }
                try {
                    ColumnSourceConfig config = OBJECT_MAPPER.readValue(
                            field.getSourceConfig(), ColumnSourceConfig.class);
                    result.put(field.getFieldCode(), config);
                } catch (Exception e) {
                    log.warn("Failed to parse ColumnSourceConfig for field {}: {}",
                            field.getFieldCode(), e.getMessage());
                }
            }
        }
        return result;
    }

    /**
     * Build char transform pipelines for each field in the sheet.
     */
    private Map<String, List<CharTransformConfig.CharRule>> buildCharPipelines(
            SheetConfigDto sheetConfig, List<CharTransformConfig.CharRule> templateRules) {
        Map<String, List<CharTransformConfig.CharRule>> cache = new HashMap<>();
        if (sheetConfig.getGroups() == null) {
            return cache;
        }
        for (GroupConfigDto group : sheetConfig.getGroups()) {
            if (group.getFields() == null) {
                continue;
            }
            for (FieldMappingDto field : group.getFields()) {
                CharTransformConfig fieldCharConfig = null;
                if (field.getTransformConfig() != null && !field.getTransformConfig().isBlank()) {
                    try {
                        var tc = OBJECT_MAPPER.readValue(field.getTransformConfig(),
                                com.eiss.erp.defineimport.model.config.TransformConfig.class);
                        fieldCharConfig = tc.getCharTransform();
                    } catch (Exception ignored) {
                    }
                }
                List<CharTransformConfig.CharRule> pipeline =
                        CharTransformer.buildPipeline(null, templateRules, fieldCharConfig);
                if (!pipeline.isEmpty()) {
                    cache.put(field.getFieldCode(), pipeline);
                }
            }
        }
        return cache;
    }

    /**
     * Find price match rule from sheet configs (uses the first one found on a price sheet,
     * or falls back to the first inventory sheet's rule).
     */
    private PriceMatchRuleDto findPriceMatchRule(List<SheetConfigDto> sheets) {
        for (SheetConfigDto sheet : sheets) {
            if (sheet.getPriceMatchRule() != null) {
                return sheet.getPriceMatchRule();
            }
        }
        return null;
    }

    private ImportPreviewResult buildResult(String templateName,
                                            List<SheetConfigDto> sheets,
                                            List<ParsedRowDto> inventoryRows,
                                            List<ParsedRowDto> priceRows,
                                            List<ExcelImportError> errors,
                                            DuplicateDetector duplicateDetector) {
        ImportPreviewResult result = new ImportPreviewResult();
        result.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        result.setTemplateName(templateName);
        result.setInventoryRows(inventoryRows);
        result.setPriceRows(priceRows);
        result.setErrors(errors);

        ImportPreviewResult.ImportSummary summary = new ImportPreviewResult.ImportSummary();
        summary.setTotalSheets(sheets.size());

        int invSheets = 0, priceSheets = 0;
        for (SheetConfigDto s : sheets) {
            if (s.getContentType() != null && s.getContentType() == ContentTypeEnum.INVENTORY.getCode()) {
                invSheets++;
            } else {
                priceSheets++;
            }
        }
        summary.setInventorySheets(invSheets);
        summary.setPriceSheets(priceSheets);
        summary.setTotalRows(inventoryRows.size() + priceRows.size());
        summary.setSuccessRows(inventoryRows.size());

        long errorRowCount = errors.stream()
                .filter(e -> "ERROR".equals(e.getErrorLevel()))
                .map(ExcelImportError::getRowIndex)
                .filter(Objects::nonNull)
                .distinct()
                .count();
        summary.setErrorRows((int) errorRowCount);
        summary.setDuplicateRows(duplicateDetector.getDuplicateCount());

        long priceMatchedCount = inventoryRows.stream()
                .filter(r -> r.getPrice() != null)
                .count();
        summary.setPriceMatchedRows((int) priceMatchedCount);
        summary.setPriceUnmatchedRows(inventoryRows.size() - (int) priceMatchedCount);

        result.setSummary(summary);
        return result;
    }

    private ImportPreviewResult buildEmptyResult(String templateName) {
        ImportPreviewResult result = new ImportPreviewResult();
        result.setTaskId(UUID.randomUUID().toString().replace("-", ""));
        result.setTemplateName(templateName);
        result.setInventoryRows(Collections.emptyList());
        result.setPriceRows(Collections.emptyList());
        result.setErrors(Collections.emptyList());
        result.setSummary(new ImportPreviewResult.ImportSummary());
        return result;
    }

    private byte[] toByteArray(InputStream inputStream) {
        try (ByteArrayOutputStream bos = new ByteArrayOutputStream()) {
            byte[] buf = new byte[8192];
            int len;
            while ((len = inputStream.read(buf)) != -1) {
                bos.write(buf, 0, len);
            }
            return bos.toByteArray();
        } catch (IOException e) {
            throw new RuntimeException("Failed to read Excel input stream", e);
        }
    }

    /**
     * First pass listener: records all cell values and merge regions.
     */
    private static class FirstPassListener extends AnalysisEventListener<Map<Integer, String>> {

        private final MergeCellCollector mergeCellCollector;
        private final Map<Integer, String> headerRowData;
        private final Integer headerRowIndex;

        FirstPassListener(MergeCellCollector mergeCellCollector,
                          Map<Integer, String> headerRowData,
                          Integer headerRowIndex) {
            this.mergeCellCollector = mergeCellCollector;
            this.headerRowData = headerRowData;
            this.headerRowIndex = headerRowIndex;
        }

        @Override
        public void invoke(Map<Integer, String> rowData, AnalysisContext context) {
            int rowIdx = context.readRowHolder().getRowIndex();
            if (rowData != null) {
                for (Map.Entry<Integer, String> entry : rowData.entrySet()) {
                    if (entry.getValue() != null) {
                        mergeCellCollector.addCellValue(rowIdx, entry.getKey(), entry.getValue());
                    }
                }
            }
            if (headerRowIndex != null && rowIdx == headerRowIndex && rowData != null) {
                headerRowData.putAll(rowData);
            }
        }

        @Override
        public void extra(CellExtra extra, AnalysisContext context) {
            if (extra != null && extra.getType() == CellExtraTypeEnum.MERGE) {
                mergeCellCollector.addMergeRegion(extra);
            }
        }

        @Override
        public void doAfterAllAnalysed(AnalysisContext context) {
            // first pass complete
        }
    }
}
