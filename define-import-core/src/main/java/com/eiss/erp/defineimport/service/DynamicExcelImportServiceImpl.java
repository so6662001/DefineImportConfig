package com.eiss.erp.defineimport.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eiss.erp.defineimport.engine.DynamicExcelParser;
import com.eiss.erp.defineimport.engine.PriceWritebackExecutor;
import com.eiss.erp.defineimport.mapper.ImportCharRulePresetMapper;
import com.eiss.erp.defineimport.mapper.ImportInventoryDataMapper;
import com.eiss.erp.defineimport.mapper.ImportRecordMapper;
import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import com.eiss.erp.defineimport.model.dto.ExcelImportError;
import com.eiss.erp.defineimport.model.dto.ImportPreviewResult;
import com.eiss.erp.defineimport.model.dto.ImportTemplateDto;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;
import com.eiss.erp.defineimport.model.dto.PriceMatchRuleDto;
import com.eiss.erp.defineimport.model.dto.SheetConfigDto;
import com.eiss.erp.defineimport.model.entity.ImportCharRulePreset;
import com.eiss.erp.defineimport.model.entity.ImportInventoryData;
import com.eiss.erp.defineimport.model.entity.ImportRecord;
import com.eiss.erp.defineimport.model.enums.ImportStatusEnum;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 动态Excel导入服务实现
 */
@Service
public class DynamicExcelImportServiceImpl implements DynamicExcelImportService {

    private static final Logger log = LoggerFactory.getLogger(DynamicExcelImportServiceImpl.class);

    @Autowired
    private ImportTemplateService importTemplateService;

    @Autowired
    private ImportRecordMapper importRecordMapper;

    @Autowired
    private ImportInventoryDataMapper inventoryDataMapper;

    @Autowired
    private ImportCharRulePresetMapper importCharRulePresetMapper;

    @Autowired
    private PriceWritebackExecutor priceWritebackExecutor;

    private final DynamicExcelParser parser = new DynamicExcelParser();

    @Override
    public ImportPreviewResult previewImport(InputStream fileStream, Long templateId) {
        ImportTemplateDto templateDto = requireTemplate(templateId);
        return parser.parse(fileStream, templateDto, loadPresetCharRules());
    }

    @Override
    public ImportPreviewResult previewInventoryImport(InputStream fileStream, Long templateId) {
        ImportTemplateDto templateDto = requireTemplate(templateId);
        return parser.parseInventoryOnly(fileStream, templateDto, loadPresetCharRules());
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public ImportPreviewResult previewPriceImport(InputStream fileStream, Long templateId,
                                                  Long inventoryRecordId, String batchNo) {
        ImportTemplateDto templateDto = requireTemplate(templateId);
        ImportPreviewResult result = parser.parsePriceOnly(fileStream, templateDto, loadPresetCharRules());

        PriceMatchRuleDto rule = findPriceMatchRule(templateDto);
        List<String> matchFields = rule != null && rule.getMatchFields() != null
                ? rule.getMatchFields()
                : List.of("category", "spec", "origin", "material");
        int wtMode = rule != null && rule.getWallThicknessMatchMode() != null
                ? rule.getWallThicknessMatchMode() : 0;
        int srMode = rule != null && rule.getSpecRangeMatchMode() != null
                ? rule.getSpecRangeMatchMode() : 0;
        SpecRangeConfig srConfig = rule != null ? rule.getSpecRangeConfig() : null;

        PriceWritebackExecutor.WritebackResult wb = priceWritebackExecutor.execute(
                result.getPriceRows(),
                inventoryRecordId,
                batchNo,
                templateDto.getSupplierId(),
                matchFields,
                wtMode,
                srMode,
                srConfig,
                PriceWritebackExecutor.WRITE_UPDATE_IF_CHANGED);

        if (result.getSummary() != null) {
            result.getSummary().setPriceMatchedRows(wb.getMatchedRows());
            result.getSummary().setPriceUnmatchedRows(wb.getUnmatchedRows());
        }
        if (wb.getUnmatchedDetails() != null && !wb.getUnmatchedDetails().isEmpty()) {
            List<ExcelImportError> merged = new ArrayList<>(
                    result.getErrors() != null ? result.getErrors() : Collections.emptyList());
            merged.addAll(wb.getUnmatchedDetails());
            result.setErrors(merged);
        }

        log.info("Price preview writeback: matched={}, unmatched={}, updated={}, skipped={}",
                wb.getMatchedRows(), wb.getUnmatchedRows(), wb.getUpdatedRows(), wb.getSkippedRows());
        return result;
    }

    private ImportTemplateDto requireTemplate(Long templateId) {
        ImportTemplateDto templateDto = importTemplateService.getTemplateById(templateId);
        if (templateDto == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }
        return templateDto;
    }

    /**
     * 加载启用的全局字符转换预设，供解析器与模板级、字段级规则合并。
     */
    private List<CharTransformConfig.CharRule> loadPresetCharRules() {
        List<ImportCharRulePreset> presets = importCharRulePresetMapper.selectList(
                new LambdaQueryWrapper<ImportCharRulePreset>()
                        .eq(ImportCharRulePreset::getEnabled, 1)
                        .orderByAsc(ImportCharRulePreset::getSortOrder));
        if (presets == null || presets.isEmpty()) {
            return Collections.emptyList();
        }
        List<CharTransformConfig.CharRule> rules = new ArrayList<>(presets.size());
        for (ImportCharRulePreset p : presets) {
            CharTransformConfig.CharRule r = new CharTransformConfig.CharRule();
            r.setMatchType(p.getMatchType());
            r.setMatchPattern(p.getMatchPattern());
            r.setReplaceValue(p.getReplaceValue());
            r.setSortOrder(p.getSortOrder());
            r.setDescription(p.getDescription());
            rules.add(r);
        }
        return rules;
    }

    private static PriceMatchRuleDto findPriceMatchRule(ImportTemplateDto templateDto) {
        if (templateDto.getSheets() == null) {
            return null;
        }
        for (SheetConfigDto sheet : templateDto.getSheets()) {
            if (sheet.getPriceMatchRule() != null) {
                return sheet.getPriceMatchRule();
            }
        }
        return null;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long confirmImport(Long templateId, List<ParsedRowDto> rows,
                              String fileName, String filePath, int importType, String batchNo) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("导入数据不能为空");
        }

        ImportTemplateDto template = importTemplateService.getTemplateById(templateId);
        if (template == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }

        String effectiveBatchNo = generateBatchNoIfBlank(batchNo);
        String safeFileName = fileName != null ? fileName : "";
        String safeFilePath = filePath != null ? filePath : "";

        ImportRecord record = new ImportRecord();
        record.setTemplateId(templateId);
        record.setTemplateCode(template.getTemplateCode());
        record.setSupplierId(template.getSupplierId());
        record.setSupplierName(template.getSupplierName());
        record.setFileName(safeFileName);
        record.setFilePath(safeFilePath);
        record.setImportType(importType);
        record.setBatchNo(effectiveBatchNo);
        record.setTotalRows(rows.size());
        record.setSuccessRows(rows.size());
        record.setErrorRows(0);
        record.setImportStatus(ImportStatusEnum.CONFIRMED.getCode());
        record.setDeleted(0);
        importRecordMapper.insert(record);
        Long recordId = record.getId();

        Long supplierId = template.getSupplierId();
        for (ParsedRowDto row : rows) {
            ImportInventoryData data = new ImportInventoryData();
            data.setRecordId(recordId);
            data.setBatchNo(effectiveBatchNo);
            data.setSupplierId(supplierId);
            data.setCategory(row.getCategory());
            data.setSpec(row.getSpec());
            data.setOrigin(row.getOrigin());
            data.setMaterial(row.getMaterial());
            data.setPackageNum(row.getPackageNum());
            data.setWholeNum(row.getWholeNum());
            data.setOddNum(row.getOddNum());
            data.setWeight(row.getWeight());
            data.setPrice(row.getPrice());
            data.setRemark(row.getRemark());
            data.setSourceSheet(row.getSheetName());
            data.setSourceRow(row.getRowIndex());
            data.setDeleted(0);
            inventoryDataMapper.insert(data);
        }

        log.info("Import confirmed. recordId={}, templateId={}, batchNo={}, rows={}",
                recordId, templateId, effectiveBatchNo, rows.size());
        return recordId;
    }

    /**
     * 若 batchNo 为空则生成 B+yyyyMMdd+三位序号。
     */
    private String generateBatchNoIfBlank(String batchNo) {
        if (batchNo != null && !batchNo.isBlank()) {
            return batchNo.trim();
        }
        String day = LocalDate.now().format(DateTimeFormatter.BASIC_ISO_DATE);
        String prefix = "B" + day;
        ImportRecord last = importRecordMapper.selectOne(new LambdaQueryWrapper<ImportRecord>()
                .likeRight(ImportRecord::getBatchNo, prefix)
                .orderByDesc(ImportRecord::getBatchNo)
                .last("LIMIT 1"));
        int nextSeq = 1;
        if (last != null && last.getBatchNo() != null && last.getBatchNo().startsWith(prefix)
                && last.getBatchNo().length() > prefix.length()) {
            try {
                nextSeq = Integer.parseInt(last.getBatchNo().substring(prefix.length())) + 1;
            } catch (NumberFormatException ignored) {
                nextSeq = 1;
            }
        }
        return prefix + String.format("%03d", nextSeq);
    }
}
