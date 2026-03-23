package com.eiss.erp.defineimport.service;

import com.eiss.erp.defineimport.engine.DynamicExcelParser;
import com.eiss.erp.defineimport.mapper.ImportCharRulePresetMapper;
import com.eiss.erp.defineimport.mapper.ImportInventoryDataMapper;
import com.eiss.erp.defineimport.mapper.ImportRecordMapper;
import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import com.eiss.erp.defineimport.model.entity.ImportCharRulePreset;
import com.eiss.erp.defineimport.model.dto.ImportPreviewResult;
import com.eiss.erp.defineimport.model.dto.ImportTemplateDto;
import com.eiss.erp.defineimport.model.dto.ParsedRowDto;
import com.eiss.erp.defineimport.model.entity.ImportInventoryData;
import com.eiss.erp.defineimport.model.entity.ImportRecord;
import com.eiss.erp.defineimport.model.enums.ImportStatusEnum;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

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

    private final DynamicExcelParser parser = new DynamicExcelParser();

    @Override
    public ImportPreviewResult previewImport(InputStream fileStream, Long templateId) {
        ImportTemplateDto templateDto = importTemplateService.getTemplateById(templateId);
        if (templateDto == null) {
            throw new IllegalArgumentException("模板不存在: " + templateId);
        }

        return parser.parse(fileStream, templateDto, loadPresetCharRules());
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

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long confirmImport(String taskId, List<ParsedRowDto> rows) {
        if (rows == null || rows.isEmpty()) {
            throw new IllegalArgumentException("导入数据不能为空");
        }

        String batchNo = UUID.randomUUID().toString().replace("-", "");

        ImportRecord record = new ImportRecord();
        record.setBatchNo(batchNo);
        record.setTotalRows(rows.size());
        record.setSuccessRows(rows.size());
        record.setErrorRows(0);
        record.setImportStatus(ImportStatusEnum.CONFIRMED.getCode());
        record.setDeleted(0);
        importRecordMapper.insert(record);
        Long recordId = record.getId();

        for (ParsedRowDto row : rows) {
            ImportInventoryData data = new ImportInventoryData();
            data.setRecordId(recordId);
            data.setBatchNo(batchNo);
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

        log.info("Import confirmed. recordId={}, batchNo={}, rows={}", recordId, batchNo, rows.size());
        return recordId;
    }
}
