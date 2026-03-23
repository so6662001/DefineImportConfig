package com.eiss.erp.demo.controller;

import com.alibaba.excel.EasyExcel;
import com.alibaba.excel.read.listener.PageReadListener;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eiss.erp.defineimport.engine.*;
import com.eiss.erp.defineimport.mapper.*;
import com.eiss.erp.defineimport.model.config.*;
import com.eiss.erp.defineimport.model.dto.*;
import com.eiss.erp.defineimport.model.entity.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.math.BigDecimal;
import java.util.*;

@RestController
@RequestMapping("/api/v1/dynamic-import")
public class DemoExcelImportController {

    @Autowired
    private ImportTemplateMapper templateMapper;
    @Autowired
    private ImportTemplateSheetMapper sheetMapper;
    @Autowired
    private ImportTemplateGroupMapper groupMapper;
    @Autowired
    private ImportTemplateFieldMapper fieldMapper;
    @Autowired
    private ImportTemplateFieldValueMappingMapper valueMappingMapper;
    @Autowired
    private ImportTemplatePriceMatchRuleMapper priceMatchRuleMapper;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @PostMapping("/preview")
    public Result<?> preview(@RequestParam("file") MultipartFile file,
                              @RequestParam("templateId") Long templateId) {
        try {
            ImportTemplate template = templateMapper.selectById(templateId);
            if (template == null) {
                return Result.fail("模板不存在: " + templateId);
            }

            List<ImportTemplateSheet> sheets = sheetMapper.selectList(
                    new LambdaQueryWrapper<ImportTemplateSheet>()
                            .eq(ImportTemplateSheet::getTemplateId, templateId)
                            .orderByAsc(ImportTemplateSheet::getSortOrder));

            List<ParsedRowDto> allInventoryRows = new ArrayList<>();
            List<ParsedRowDto> allPriceRows = new ArrayList<>();
            List<ExcelImportError> allErrors = new ArrayList<>();

            try (InputStream is = file.getInputStream()) {
                for (ImportTemplateSheet sheetConfig : sheets) {
                    List<ImportTemplateGroup> groups = groupMapper.selectList(
                            new LambdaQueryWrapper<ImportTemplateGroup>()
                                    .eq(ImportTemplateGroup::getSheetConfigId, sheetConfig.getId()));
                    List<ImportTemplateField> fields = fieldMapper.selectList(
                            new LambdaQueryWrapper<ImportTemplateField>()
                                    .eq(ImportTemplateField::getSheetConfigId, sheetConfig.getId()));

                    List<Map<Integer, String>> rawData = new ArrayList<>();
                    final Map<Integer, String>[] headerHolder = new Map[]{null};

                    EasyExcel.read(file.getInputStream(), new PageReadListener<Map<Integer, String>>(dataList -> {
                        rawData.addAll(dataList);
                    })).sheet(sheetConfig.getSheetIndex())
                            .headRowNumber(sheetConfig.getHeaderRowIndex() + 1)
                            .doRead();

                    int startRow = sheetConfig.getDataStartRowIndex() != null
                            ? sheetConfig.getDataStartRowIndex() : sheetConfig.getHeaderRowIndex() + 1;
                    int contentType = sheetConfig.getContentType() != null ? sheetConfig.getContentType() : 1;

                    for (int i = 0; i < rawData.size(); i++) {
                        Map<Integer, String> rowData = rawData.get(i);
                        int rowIdx = startRow + i + 1;

                        if (rowData == null || rowData.values().stream().allMatch(v -> v == null || v.isBlank())) {
                            continue;
                        }

                        if (groups.isEmpty()) {
                            ParsedRowDto row = buildRow(rowData, fields, null, sheetConfig, rowIdx, allErrors);
                            if (contentType == 1) {
                                allInventoryRows.add(row);
                            } else {
                                allPriceRows.add(row);
                            }
                        } else {
                            for (ImportTemplateGroup group : groups) {
                                ParsedRowDto row = buildRow(rowData, fields, group, sheetConfig, rowIdx, allErrors);
                                if (contentType == 1) {
                                    allInventoryRows.add(row);
                                } else {
                                    allPriceRows.add(row);
                                }
                            }
                        }
                    }
                }
            }

            ImportPreviewResult result = new ImportPreviewResult();
            result.setTemplateName(template.getTemplateName());
            result.setInventoryRows(allInventoryRows);
            result.setPriceRows(allPriceRows);
            result.setErrors(allErrors);

            ImportPreviewResult.ImportSummary summary = new ImportPreviewResult.ImportSummary();
            summary.setTotalSheets(sheets.size());
            summary.setInventorySheets((int) sheets.stream().filter(s -> s.getContentType() == 1).count());
            summary.setPriceSheets((int) sheets.stream().filter(s -> s.getContentType() == 2).count());
            summary.setTotalRows(allInventoryRows.size() + allPriceRows.size());
            summary.setSuccessRows(allInventoryRows.size() + allPriceRows.size() - allErrors.size());
            summary.setErrorRows(allErrors.size());
            result.setSummary(summary);

            return Result.ok(result);
        } catch (Exception e) {
            return Result.fail("导入预览失败: " + e.getMessage());
        }
    }

    private ParsedRowDto buildRow(Map<Integer, String> rowData,
                                   List<ImportTemplateField> fields,
                                   ImportTemplateGroup group,
                                   ImportTemplateSheet sheetConfig,
                                   int rowIdx,
                                   List<ExcelImportError> errors) {
        ParsedRowDto row = new ParsedRowDto();
        row.setRowIndex(rowIdx);
        row.setSheetName(sheetConfig.getSheetName());

        if (group != null) {
            row.setGroupName(group.getGroupName());
            if (group.getFixedCategory() != null) row.setCategory(group.getFixedCategory());
            if (group.getFixedOrigin() != null) row.setOrigin(group.getFixedOrigin());
            if (group.getFixedMaterial() != null) row.setMaterial(group.getFixedMaterial());
            if (group.getFixedRemark() != null) row.setRemark(group.getFixedRemark());
        }

        for (ImportTemplateField field : fields) {
            if (group != null && field.getGroupId() != null && !field.getGroupId().equals(group.getId())) {
                continue;
            }
            if (group == null && field.getGroupId() != null) {
                continue;
            }

            String value = null;
            if ("COLUMN".equals(field.getSourceType())) {
                try {
                    ColumnSourceConfig config = objectMapper.readValue(field.getSourceConfig(), ColumnSourceConfig.class);
                    Integer colIdx = config.getColumnIndex();
                    if (colIdx != null) {
                        value = rowData.get(colIdx);
                    }
                } catch (Exception ignored) {
                }
            } else if ("FIXED_VALUE".equals(field.getSourceType())) {
                try {
                    FixedValueSourceConfig config = objectMapper.readValue(field.getSourceConfig(), FixedValueSourceConfig.class);
                    value = config.getValue();
                } catch (Exception ignored) {
                }
            }

            if (value == null && field.getDefaultValue() != null) {
                value = field.getDefaultValue();
            }
            if (value != null) {
                row.setFieldValue(field.getFieldCode(), value, errors);
            }
        }
        return row;
    }
}
