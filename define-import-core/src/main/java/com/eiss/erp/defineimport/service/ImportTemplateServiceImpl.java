package com.eiss.erp.defineimport.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eiss.erp.defineimport.mapper.*;
import com.eiss.erp.defineimport.model.config.CharTransformConfig;
import com.eiss.erp.defineimport.model.config.SpecRangeConfig;
import com.eiss.erp.defineimport.model.dto.*;
import com.eiss.erp.defineimport.model.entity.*;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/**
 * 导入模板服务实现
 */
@Service
public class ImportTemplateServiceImpl implements ImportTemplateService {

    private static final Logger log = LoggerFactory.getLogger(ImportTemplateServiceImpl.class);
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    @Autowired
    private ImportTemplateMapper templateMapper;
    @Autowired
    private ImportTemplateSheetMapper sheetMapper;
    @Autowired
    private ImportTemplateGroupMapper groupMapper;
    @Autowired
    private ImportTemplateFieldMapper fieldMapper;
    @Autowired
    private ImportTemplateFieldValueMappingMapper fieldValueMappingMapper;
    @Autowired
    private ImportTemplateCharRuleMapper charRuleMapper;
    @Autowired
    private ImportCharRulePresetMapper charRulePresetMapper;
    @Autowired
    private ImportTemplatePriceMatchRuleMapper priceMatchRuleMapper;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public Long saveTemplate(ImportTemplateDto dto) {
        ImportTemplate template = toEntity(dto);
        template.setStatus(1);
        template.setDeleted(0);
        templateMapper.insert(template);
        Long templateId = template.getId();

        saveSubConfigs(templateId, dto);
        return templateId;
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void updateTemplate(ImportTemplateDto dto) {
        if (dto.getId() == null) {
            throw new IllegalArgumentException("模板ID不能为空");
        }
        Long templateId = dto.getId();

        ImportTemplate template = toEntity(dto);
        template.setId(templateId);
        templateMapper.updateById(template);

        deleteSubConfigs(templateId);
        saveSubConfigs(templateId, dto);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public void deleteTemplate(Long id) {
        templateMapper.deleteById(id);
        deleteSubConfigs(id);
    }

    @Override
    public ImportTemplateDto getTemplateById(Long id) {
        ImportTemplate template = templateMapper.selectById(id);
        if (template == null) {
            return null;
        }

        ImportTemplateDto dto = new ImportTemplateDto();
        dto.setId(template.getId());
        dto.setTemplateCode(template.getTemplateCode());
        dto.setTemplateName(template.getTemplateName());
        dto.setSupplierId(template.getSupplierId());
        dto.setSupplierName(template.getSupplierName());
        dto.setAllSheetsPrice(template.getAllSheetsPrice());

        List<ImportTemplateSheet> sheetEntities = sheetMapper.selectList(
                new LambdaQueryWrapper<ImportTemplateSheet>()
                        .eq(ImportTemplateSheet::getTemplateId, id)
                        .orderByAsc(ImportTemplateSheet::getSortOrder));

        List<ImportTemplateGroup> groupEntities = groupMapper.selectList(
                new LambdaQueryWrapper<ImportTemplateGroup>()
                        .eq(ImportTemplateGroup::getTemplateId, id));

        List<ImportTemplateField> fieldEntities = fieldMapper.selectList(
                new LambdaQueryWrapper<ImportTemplateField>()
                        .eq(ImportTemplateField::getTemplateId, id)
                        .orderByAsc(ImportTemplateField::getSortOrder));

        List<ImportTemplateFieldValueMapping> valueMappingEntities = fieldValueMappingMapper.selectList(
                new LambdaQueryWrapper<ImportTemplateFieldValueMapping>()
                        .eq(ImportTemplateFieldValueMapping::getTemplateId, id));

        List<ImportTemplatePriceMatchRule> priceRuleEntities = priceMatchRuleMapper.selectList(
                new LambdaQueryWrapper<ImportTemplatePriceMatchRule>()
                        .eq(ImportTemplatePriceMatchRule::getTemplateId, id));

        List<ImportTemplateCharRule> charRuleEntities = charRuleMapper.selectList(
                new LambdaQueryWrapper<ImportTemplateCharRule>()
                        .eq(ImportTemplateCharRule::getTemplateId, id)
                        .eq(ImportTemplateCharRule::getEnabled, 1)
                        .orderByAsc(ImportTemplateCharRule::getSortOrder));

        // Index groups by sheetConfigId
        Map<Long, List<ImportTemplateGroup>> groupsBySheet = groupEntities.stream()
                .collect(Collectors.groupingBy(ImportTemplateGroup::getSheetConfigId));

        // Index fields by groupId
        Map<Long, List<ImportTemplateField>> fieldsByGroup = fieldEntities.stream()
                .filter(f -> f.getGroupId() != null)
                .collect(Collectors.groupingBy(ImportTemplateField::getGroupId));

        // Sheet-wide fields (group_id = null): apply to every group on that sheet
        Map<Long, List<ImportTemplateField>> fieldsBySheetWithoutGroup = fieldEntities.stream()
                .filter(f -> f.getGroupId() == null && f.getSheetConfigId() != null)
                .collect(Collectors.groupingBy(ImportTemplateField::getSheetConfigId));

        // Index value mappings by groupId
        Map<Long, List<ImportTemplateFieldValueMapping>> mappingsByGroup = valueMappingEntities.stream()
                .filter(m -> m.getGroupId() != null)
                .collect(Collectors.groupingBy(ImportTemplateFieldValueMapping::getGroupId));

        // Sheet-wide value mappings (group_id = null)
        Map<Long, List<ImportTemplateFieldValueMapping>> mappingsBySheetWithoutGroup =
                valueMappingEntities.stream()
                        .filter(m -> m.getGroupId() == null && m.getSheetConfigId() != null)
                        .collect(Collectors.groupingBy(ImportTemplateFieldValueMapping::getSheetConfigId));

        // Template-level default price rule (sheet_config_id = null)
        ImportTemplatePriceMatchRule templateDefaultPriceRule = priceRuleEntities.stream()
                .filter(r -> r.getSheetConfigId() == null)
                .findFirst()
                .orElse(null);

        // Index per-sheet price rules
        Map<Long, ImportTemplatePriceMatchRule> priceRuleBySheet = priceRuleEntities.stream()
                .filter(r -> r.getSheetConfigId() != null)
                .collect(Collectors.toMap(ImportTemplatePriceMatchRule::getSheetConfigId,
                        r -> r, (a, b) -> a));

        List<SheetConfigDto> sheetDtos = new ArrayList<>();
        for (ImportTemplateSheet sheetEntity : sheetEntities) {
            SheetConfigDto sheetDto = toSheetDto(sheetEntity);

            List<ImportTemplateGroup> groups = groupsBySheet.getOrDefault(sheetEntity.getId(), Collections.emptyList());
            groups.sort(Comparator.comparing(ImportTemplateGroup::getGroupSeq,
                    Comparator.nullsLast(Integer::compareTo)));

            List<ImportTemplateField> sheetWideFields =
                    fieldsBySheetWithoutGroup.getOrDefault(sheetEntity.getId(), Collections.emptyList());
            List<ImportTemplateFieldValueMapping> sheetWideMappings =
                    mappingsBySheetWithoutGroup.getOrDefault(sheetEntity.getId(), Collections.emptyList());

            List<GroupConfigDto> groupDtos = new ArrayList<>();
            for (ImportTemplateGroup groupEntity : groups) {
                GroupConfigDto groupDto = toGroupDto(groupEntity);

                List<ImportTemplateField> fields = new ArrayList<>(
                        fieldsByGroup.getOrDefault(groupEntity.getId(), Collections.emptyList()));
                fields.addAll(sheetWideFields);
                fields.sort(Comparator.comparing(ImportTemplateField::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)));
                groupDto.setFields(fields.stream().map(this::toFieldDto).collect(Collectors.toList()));

                List<ImportTemplateFieldValueMapping> mergedMappings = new ArrayList<>(
                        mappingsByGroup.getOrDefault(groupEntity.getId(), Collections.emptyList()));
                mergedMappings.addAll(sheetWideMappings);
                mergedMappings.sort(Comparator.comparing(ImportTemplateFieldValueMapping::getSortOrder,
                        Comparator.nullsLast(Integer::compareTo)));
                groupDto.setFieldValueMappings(mergedMappings);

                groupDtos.add(groupDto);
            }
            sheetDto.setGroups(groupDtos);

            ImportTemplatePriceMatchRule priceRule = priceRuleBySheet.get(sheetEntity.getId());
            if (priceRule == null) {
                priceRule = templateDefaultPriceRule;
            }
            if (priceRule != null) {
                sheetDto.setPriceMatchRule(toPriceMatchRuleDto(priceRule));
            }

            sheetDtos.add(sheetDto);
        }
        dto.setSheets(sheetDtos);

        Set<Long> presetRuleIds = charRuleEntities.stream()
                .map(ImportTemplateCharRule::getPresetRuleId)
                .filter(Objects::nonNull)
                .collect(Collectors.toSet());
        Map<Long, ImportCharRulePreset> presetById = new HashMap<>();
        if (!presetRuleIds.isEmpty()) {
            for (ImportCharRulePreset preset : charRulePresetMapper.selectBatchIds(presetRuleIds)) {
                if (preset != null && preset.getId() != null) {
                    presetById.put(preset.getId(), preset);
                }
            }
        }

        Map<Long, ImportCharRulePreset> presetByIdFinal = presetById;
        dto.setTemplateCharRules(charRuleEntities.stream()
                .map(e -> toCharRule(e, presetByIdFinal))
                .collect(Collectors.toList()));

        return dto;
    }

    @Override
    public List<ImportTemplate> listTemplates(Long supplierId, String keyword) {
        LambdaQueryWrapper<ImportTemplate> wrapper = new LambdaQueryWrapper<>();
        if (supplierId != null) {
            wrapper.eq(ImportTemplate::getSupplierId, supplierId);
        }
        if (keyword != null && !keyword.isBlank()) {
            wrapper.and(w -> w.like(ImportTemplate::getTemplateName, keyword)
                    .or().like(ImportTemplate::getTemplateCode, keyword));
        }
        wrapper.orderByDesc(ImportTemplate::getUpdateTime);
        return templateMapper.selectList(wrapper);
    }

    // ==================== Helper methods ====================

    private void saveSubConfigs(Long templateId, ImportTemplateDto dto) {
        // Save char rules
        if (dto.getTemplateCharRules() != null) {
            int sortOrder = 0;
            for (CharTransformConfig.CharRule rule : dto.getTemplateCharRules()) {
                ImportTemplateCharRule entity = new ImportTemplateCharRule();
                entity.setTemplateId(templateId);
                entity.setApplyFieldCodes(rule.getApplyFieldCodes());
                entity.setPresetRuleId(rule.getPresetRuleId());
                entity.setMatchType(rule.getMatchType());
                entity.setMatchPattern(rule.getMatchPattern());
                entity.setReplaceValue(rule.getReplaceValue());
                entity.setDescription(rule.getDescription());
                entity.setSortOrder(rule.getSortOrder() != null ? rule.getSortOrder() : sortOrder++);
                entity.setEnabled(1);
                entity.setDeleted(0);
                charRuleMapper.insert(entity);
            }
        }

        // Save sheets → groups → fields → value mappings → price rules
        if (dto.getSheets() != null) {
            for (SheetConfigDto sheetDto : dto.getSheets()) {
                ImportTemplateSheet sheetEntity = toSheetEntity(templateId, sheetDto);
                sheetEntity.setDeleted(0);
                sheetMapper.insert(sheetEntity);
                Long sheetId = sheetEntity.getId();

                // Price match rule
                if (sheetDto.getPriceMatchRule() != null) {
                    savePriceMatchRule(templateId, sheetId, sheetDto.getPriceMatchRule());
                }

                // Groups
                if (sheetDto.getGroups() != null) {
                    for (GroupConfigDto groupDto : sheetDto.getGroups()) {
                        ImportTemplateGroup groupEntity = toGroupEntity(templateId, sheetId, groupDto);
                        groupEntity.setDeleted(0);
                        groupMapper.insert(groupEntity);
                        Long groupId = groupEntity.getId();

                        // Fields
                        if (groupDto.getFields() != null) {
                            int fieldSort = 0;
                            for (FieldMappingDto fieldDto : groupDto.getFields()) {
                                ImportTemplateField fieldEntity = toFieldEntity(templateId, sheetId, groupId, fieldDto);
                                fieldEntity.setSortOrder(fieldSort++);
                                fieldEntity.setDeleted(0);
                                fieldMapper.insert(fieldEntity);
                            }
                        }

                        // Value mappings
                        if (groupDto.getFieldValueMappings() != null) {
                            for (ImportTemplateFieldValueMapping mapping : groupDto.getFieldValueMappings()) {
                                mapping.setId(null);
                                mapping.setTemplateId(templateId);
                                mapping.setSheetConfigId(sheetId);
                                mapping.setGroupId(groupId);
                                mapping.setDeleted(0);
                                fieldValueMappingMapper.insert(mapping);
                            }
                        }
                    }
                }
            }
        }
    }

    private void savePriceMatchRule(Long templateId, Long sheetId, PriceMatchRuleDto ruleDto) {
        ImportTemplatePriceMatchRule entity = new ImportTemplatePriceMatchRule();
        entity.setTemplateId(templateId);
        entity.setSheetConfigId(sheetId);
        if (ruleDto.getMatchFields() != null) {
            entity.setMatchFields(String.join(",", ruleDto.getMatchFields()));
        }
        entity.setWallThicknessMatchMode(ruleDto.getWallThicknessMatchMode());
        entity.setSpecRangeMatchMode(ruleDto.getSpecRangeMatchMode());
        if (ruleDto.getSpecRangeConfig() != null) {
            try {
                entity.setSpecRangeConfig(OBJECT_MAPPER.writeValueAsString(ruleDto.getSpecRangeConfig()));
            } catch (Exception e) {
                log.warn("Failed to serialize SpecRangeConfig: {}", e.getMessage());
            }
        }
        entity.setDeleted(0);
        priceMatchRuleMapper.insert(entity);
    }

    private void deleteSubConfigs(Long templateId) {
        sheetMapper.delete(new LambdaQueryWrapper<ImportTemplateSheet>()
                .eq(ImportTemplateSheet::getTemplateId, templateId));
        groupMapper.delete(new LambdaQueryWrapper<ImportTemplateGroup>()
                .eq(ImportTemplateGroup::getTemplateId, templateId));
        fieldMapper.delete(new LambdaQueryWrapper<ImportTemplateField>()
                .eq(ImportTemplateField::getTemplateId, templateId));
        fieldValueMappingMapper.delete(new LambdaQueryWrapper<ImportTemplateFieldValueMapping>()
                .eq(ImportTemplateFieldValueMapping::getTemplateId, templateId));
        charRuleMapper.delete(new LambdaQueryWrapper<ImportTemplateCharRule>()
                .eq(ImportTemplateCharRule::getTemplateId, templateId));
        priceMatchRuleMapper.delete(new LambdaQueryWrapper<ImportTemplatePriceMatchRule>()
                .eq(ImportTemplatePriceMatchRule::getTemplateId, templateId));
    }

    // ==================== Entity ↔ DTO conversion ====================

    private ImportTemplate toEntity(ImportTemplateDto dto) {
        ImportTemplate entity = new ImportTemplate();
        entity.setTemplateCode(dto.getTemplateCode());
        entity.setTemplateName(dto.getTemplateName());
        entity.setSupplierId(dto.getSupplierId());
        entity.setSupplierName(dto.getSupplierName());
        entity.setAllSheetsPrice(dto.getAllSheetsPrice());
        return entity;
    }

    private ImportTemplateSheet toSheetEntity(Long templateId, SheetConfigDto dto) {
        ImportTemplateSheet entity = new ImportTemplateSheet();
        entity.setTemplateId(templateId);
        entity.setSheetIndex(dto.getSheetIndex());
        entity.setSheetName(dto.getSheetName());
        entity.setContentType(dto.getContentType());
        entity.setHeaderRowIndex(dto.getHeaderRowIndex());
        entity.setDataStartRowIndex(dto.getDataStartRowIndex());
        entity.setDataEndRowIndex(dto.getDataEndRowIndex());
        entity.setEmptyRowThreshold(dto.getEmptyRowThreshold());
        entity.setEnableMergeCell(dto.getEnableMergeCell());
        entity.setSortOrder(dto.getSortOrder());
        return entity;
    }

    private ImportTemplateGroup toGroupEntity(Long templateId, Long sheetId, GroupConfigDto dto) {
        ImportTemplateGroup entity = new ImportTemplateGroup();
        entity.setTemplateId(templateId);
        entity.setSheetConfigId(sheetId);
        entity.setGroupSeq(dto.getGroupSeq());
        entity.setGroupName(dto.getGroupName());
        entity.setFixedCategory(dto.getFixedCategory());
        entity.setFixedOrigin(dto.getFixedOrigin());
        entity.setFixedMaterial(dto.getFixedMaterial());
        entity.setFixedRemark(dto.getFixedRemark());
        entity.setDataStartRow(dto.getDataStartRow());
        entity.setDataEndRow(dto.getDataEndRow());
        return entity;
    }

    private ImportTemplateField toFieldEntity(Long templateId, Long sheetId, Long groupId, FieldMappingDto dto) {
        ImportTemplateField entity = new ImportTemplateField();
        entity.setTemplateId(templateId);
        entity.setSheetConfigId(sheetId);
        entity.setGroupId(groupId);
        entity.setFieldCode(dto.getFieldCode());
        entity.setFieldName(dto.getFieldName());
        entity.setSourceType(dto.getSourceType());
        entity.setSourceConfig(dto.getSourceConfig());
        entity.setTransformConfig(dto.getTransformConfig());
        entity.setRequired(dto.getRequired());
        entity.setDefaultValue(dto.getDefaultValue());
        return entity;
    }

    private SheetConfigDto toSheetDto(ImportTemplateSheet entity) {
        SheetConfigDto dto = new SheetConfigDto();
        dto.setId(entity.getId());
        dto.setSheetIndex(entity.getSheetIndex());
        dto.setSheetName(entity.getSheetName());
        dto.setContentType(entity.getContentType());
        dto.setHeaderRowIndex(entity.getHeaderRowIndex());
        dto.setDataStartRowIndex(entity.getDataStartRowIndex());
        dto.setDataEndRowIndex(entity.getDataEndRowIndex());
        dto.setEmptyRowThreshold(entity.getEmptyRowThreshold());
        dto.setEnableMergeCell(entity.getEnableMergeCell());
        dto.setSortOrder(entity.getSortOrder());
        return dto;
    }

    private GroupConfigDto toGroupDto(ImportTemplateGroup entity) {
        GroupConfigDto dto = new GroupConfigDto();
        dto.setId(entity.getId());
        dto.setGroupSeq(entity.getGroupSeq());
        dto.setGroupName(entity.getGroupName());
        dto.setFixedCategory(entity.getFixedCategory());
        dto.setFixedOrigin(entity.getFixedOrigin());
        dto.setFixedMaterial(entity.getFixedMaterial());
        dto.setFixedRemark(entity.getFixedRemark());
        dto.setDataStartRow(entity.getDataStartRow());
        dto.setDataEndRow(entity.getDataEndRow());
        return dto;
    }

    private FieldMappingDto toFieldDto(ImportTemplateField entity) {
        FieldMappingDto dto = new FieldMappingDto();
        dto.setId(entity.getId());
        dto.setFieldCode(entity.getFieldCode());
        dto.setFieldName(entity.getFieldName());
        dto.setSourceType(entity.getSourceType());
        dto.setSourceConfig(entity.getSourceConfig());
        dto.setTransformConfig(entity.getTransformConfig());
        dto.setRequired(entity.getRequired());
        dto.setDefaultValue(entity.getDefaultValue());
        return dto;
    }

    private PriceMatchRuleDto toPriceMatchRuleDto(ImportTemplatePriceMatchRule entity) {
        PriceMatchRuleDto dto = new PriceMatchRuleDto();
        if (entity.getMatchFields() != null && !entity.getMatchFields().isBlank()) {
            dto.setMatchFields(Arrays.asList(entity.getMatchFields().split(",")));
        }
        dto.setWallThicknessMatchMode(entity.getWallThicknessMatchMode());
        dto.setSpecRangeMatchMode(entity.getSpecRangeMatchMode());
        if (entity.getSpecRangeConfig() != null && !entity.getSpecRangeConfig().isBlank()) {
            try {
                dto.setSpecRangeConfig(OBJECT_MAPPER.readValue(
                        entity.getSpecRangeConfig(), SpecRangeConfig.class));
            } catch (Exception e) {
                log.warn("Failed to parse SpecRangeConfig: {}", e.getMessage());
            }
        }
        return dto;
    }

    private CharTransformConfig.CharRule toCharRule(ImportTemplateCharRule entity,
            Map<Long, ImportCharRulePreset> presetById) {
        CharTransformConfig.CharRule rule = new CharTransformConfig.CharRule();
        rule.setApplyFieldCodes(entity.getApplyFieldCodes());
        rule.setPresetRuleId(entity.getPresetRuleId());
        rule.setSortOrder(entity.getSortOrder());
        rule.setDescription(entity.getDescription());

        if (entity.getPresetRuleId() != null) {
            ImportCharRulePreset preset = presetById != null ? presetById.get(entity.getPresetRuleId()) : null;
            if (preset != null) {
                rule.setMatchType(preset.getMatchType());
                rule.setMatchPattern(preset.getMatchPattern());
                rule.setReplaceValue(preset.getReplaceValue());
            } else {
                rule.setMatchType(entity.getMatchType());
                rule.setMatchPattern(entity.getMatchPattern());
                rule.setReplaceValue(entity.getReplaceValue());
            }
        } else {
            rule.setMatchType(entity.getMatchType());
            rule.setMatchPattern(entity.getMatchPattern());
            rule.setReplaceValue(entity.getReplaceValue());
        }
        return rule;
    }
}
