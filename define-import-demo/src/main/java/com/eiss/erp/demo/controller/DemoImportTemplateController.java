package com.eiss.erp.demo.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.eiss.erp.defineimport.mapper.*;
import com.eiss.erp.defineimport.model.entity.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import org.springframework.transaction.annotation.Transactional;

import java.util.*;

@RestController
@RequestMapping("/api/v1/import-template")
public class DemoImportTemplateController {

    private static final Logger log = LoggerFactory.getLogger(DemoImportTemplateController.class);

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
    @Autowired
    private ImportTemplateCharRuleMapper charRuleMapper;

    @PostMapping
    @Transactional(rollbackFor = Exception.class)
    public Result<?> create(@RequestBody Map<String, Object> dto) {
        try {
            String templateCode = (String) dto.get("templateCode");
            String templateName = (String) dto.get("templateName");
            if (templateCode == null || templateCode.isBlank()) {
                return Result.fail("模板编码不能为空");
            }
            if (templateCode.length() > 64) {
                return Result.fail("模板编码长度不能超过64个字符");
            }
            if (templateName == null || templateName.isBlank()) {
                return Result.fail("模板名称不能为空");
            }
            if (templateName.length() > 128) {
                return Result.fail("模板名称长度不能超过128个字符");
            }

            ImportTemplate template = new ImportTemplate();
            template.setTemplateCode(templateCode);
            template.setTemplateName(templateName);
            template.setSupplierId(toLong(dto.get("supplierId")));
            template.setSupplierName((String) dto.get("supplierName"));
            template.setScope((String) dto.get("scope"));
            template.setAllSheetsPrice(toInt(dto.get("allSheetsPrice"), 0));
            template.setStatus(toInt(dto.get("status"), 1));
            template.setRemark((String) dto.get("remark"));
            templateMapper.insert(template);
            Long templateId = template.getId();

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sheets = (List<Map<String, Object>>) dto.get("sheets");
            if (sheets != null) {
                for (Map<String, Object> s : sheets) {
                    ImportTemplateSheet sheet = new ImportTemplateSheet();
                    sheet.setTemplateId(templateId);
                    sheet.setSheetIndex(toInt(s.get("sheetIndex"), 0));
                    sheet.setSheetName((String) s.get("sheetName"));
                    sheet.setContentType(toInt(s.get("contentType"), 1));
                    sheet.setHeaderRowIndex(toInt(s.get("headerRowIndex"), 0));
                    sheet.setDataStartRowIndex(toIntOrNull(s.get("dataStartRowIndex")));
                    sheet.setDataEndRowIndex(toIntOrNull(s.get("dataEndRowIndex")));
                    sheet.setEmptyRowThreshold(toInt(s.get("emptyRowThreshold"), 2));
                    sheet.setEnableMergeCell(toInt(s.get("enableMergeCell"), 1));
                    sheet.setSortOrder(toInt(s.get("sortOrder"), 0));
                    sheetMapper.insert(sheet);
                    Long sheetId = sheet.getId();

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> groups = (List<Map<String, Object>>) s.get("groups");
                    if (groups != null) {
                        for (Map<String, Object> g : groups) {
                            ImportTemplateGroup group = new ImportTemplateGroup();
                            group.setTemplateId(templateId);
                            group.setSheetConfigId(sheetId);
                            group.setGroupSeq(toInt(g.get("groupSeq"), 1));
                            group.setGroupName((String) g.get("groupName"));
                            group.setFixedCategory((String) g.get("fixedCategory"));
                            group.setFixedOrigin((String) g.get("fixedOrigin"));
                            group.setFixedMaterial((String) g.get("fixedMaterial"));
                            group.setFixedRemark((String) g.get("fixedRemark"));
                            group.setDataStartRow(toIntOrNull(g.get("dataStartRow")));
                            group.setDataEndRow(toIntOrNull(g.get("dataEndRow")));
                            groupMapper.insert(group);
                            Long groupId = group.getId();

                            saveFields(templateId, sheetId, groupId, g);
                            saveValueMappings(templateId, sheetId, groupId, g);
                        }
                    }

                    saveFields(templateId, sheetId, null, s);
                    saveValueMappings(templateId, sheetId, null, s);

                    @SuppressWarnings("unchecked")
                    Map<String, Object> pmr = (Map<String, Object>) s.get("priceMatchRule");
                    if (pmr != null) {
                        ImportTemplatePriceMatchRule rule = new ImportTemplatePriceMatchRule();
                        rule.setTemplateId(templateId);
                        rule.setSheetConfigId(sheetId);
                        rule.setMatchFields(toJsonString(pmr.get("matchFields")));
                        rule.setWallThicknessMatchMode(toInt(pmr.get("wallThicknessMatchMode"), 0));
                        rule.setSpecRangeMatchMode(toInt(pmr.get("specRangeMatchMode"), 0));
                        rule.setSpecRangeConfig(toJsonString(pmr.get("specRangeConfig")));
                        priceMatchRuleMapper.insert(rule);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> charRules = (List<Map<String, Object>>) dto.get("charRules");
            if (charRules != null) {
                for (Map<String, Object> cr : charRules) {
                    ImportTemplateCharRule rule = new ImportTemplateCharRule();
                    rule.setTemplateId(templateId);
                    rule.setApplyFieldCodes(toJsonString(cr.get("applyFieldCodes")));
                    rule.setPresetRuleId(toLong(cr.get("presetRuleId")));
                    rule.setMatchType((String) cr.get("matchType"));
                    rule.setMatchPattern((String) cr.get("matchPattern"));
                    rule.setReplaceValue((String) cr.get("replaceValue"));
                    rule.setDescription((String) cr.get("description"));
                    rule.setSortOrder(toInt(cr.get("sortOrder"), 0));
                    rule.setEnabled(toInt(cr.get("enabled"), 1));
                    charRuleMapper.insert(rule);
                }
            }

            return Result.ok(Map.of("id", templateId));
        } catch (Exception e) {
            log.error("创建模板失败", e);
            return Result.fail("创建模板失败，请检查输入数据格式");
        }
    }

    @PutMapping("/{id}")
    @Transactional
    public Result<?> update(@PathVariable Long id, @RequestBody Map<String, Object> dto) {
        try {
            ImportTemplate existing = templateMapper.selectById(id);
            if (existing == null) {
                return Result.fail("模板不存在");
            }

            // Delete all existing sub-records
            sheetMapper.delete(new LambdaQueryWrapper<ImportTemplateSheet>().eq(ImportTemplateSheet::getTemplateId, id));
            groupMapper.delete(new LambdaQueryWrapper<ImportTemplateGroup>().eq(ImportTemplateGroup::getTemplateId, id));
            fieldMapper.delete(new LambdaQueryWrapper<ImportTemplateField>().eq(ImportTemplateField::getTemplateId, id));
            valueMappingMapper.delete(new LambdaQueryWrapper<ImportTemplateFieldValueMapping>().eq(ImportTemplateFieldValueMapping::getTemplateId, id));
            priceMatchRuleMapper.delete(new LambdaQueryWrapper<ImportTemplatePriceMatchRule>().eq(ImportTemplatePriceMatchRule::getTemplateId, id));
            charRuleMapper.delete(new LambdaQueryWrapper<ImportTemplateCharRule>().eq(ImportTemplateCharRule::getTemplateId, id));

            // Update the main template record
            existing.setTemplateCode((String) dto.get("templateCode"));
            existing.setTemplateName((String) dto.get("templateName"));
            existing.setSupplierId(toLong(dto.get("supplierId")));
            existing.setSupplierName((String) dto.get("supplierName"));
            existing.setScope((String) dto.get("scope"));
            existing.setAllSheetsPrice(toInt(dto.get("allSheetsPrice"), 0));
            existing.setStatus(toInt(dto.get("status"), 1));
            existing.setRemark((String) dto.get("remark"));
            templateMapper.updateById(existing);
            Long templateId = id;

            // Re-create sub-records (same logic as create)
            @SuppressWarnings("unchecked")
            List<Map<String, Object>> sheets = (List<Map<String, Object>>) dto.get("sheets");
            if (sheets != null) {
                for (Map<String, Object> s : sheets) {
                    ImportTemplateSheet sheet = new ImportTemplateSheet();
                    sheet.setTemplateId(templateId);
                    sheet.setSheetIndex(toInt(s.get("sheetIndex"), 0));
                    sheet.setSheetName((String) s.get("sheetName"));
                    sheet.setContentType(toInt(s.get("contentType"), 1));
                    sheet.setHeaderRowIndex(toInt(s.get("headerRowIndex"), 0));
                    sheet.setDataStartRowIndex(toIntOrNull(s.get("dataStartRowIndex")));
                    sheet.setDataEndRowIndex(toIntOrNull(s.get("dataEndRowIndex")));
                    sheet.setEmptyRowThreshold(toInt(s.get("emptyRowThreshold"), 2));
                    sheet.setEnableMergeCell(toInt(s.get("enableMergeCell"), 1));
                    sheet.setSortOrder(toInt(s.get("sortOrder"), 0));
                    sheetMapper.insert(sheet);
                    Long sheetId = sheet.getId();

                    @SuppressWarnings("unchecked")
                    List<Map<String, Object>> groups = (List<Map<String, Object>>) s.get("groups");
                    if (groups != null) {
                        for (Map<String, Object> g : groups) {
                            ImportTemplateGroup group = new ImportTemplateGroup();
                            group.setTemplateId(templateId);
                            group.setSheetConfigId(sheetId);
                            group.setGroupSeq(toInt(g.get("groupSeq"), 1));
                            group.setGroupName((String) g.get("groupName"));
                            group.setFixedCategory((String) g.get("fixedCategory"));
                            group.setFixedOrigin((String) g.get("fixedOrigin"));
                            group.setFixedMaterial((String) g.get("fixedMaterial"));
                            group.setFixedRemark((String) g.get("fixedRemark"));
                            group.setDataStartRow(toIntOrNull(g.get("dataStartRow")));
                            group.setDataEndRow(toIntOrNull(g.get("dataEndRow")));
                            groupMapper.insert(group);
                            Long groupId = group.getId();

                            saveFields(templateId, sheetId, groupId, g);
                            saveValueMappings(templateId, sheetId, groupId, g);
                        }
                    }

                    saveFields(templateId, sheetId, null, s);
                    saveValueMappings(templateId, sheetId, null, s);

                    @SuppressWarnings("unchecked")
                    Map<String, Object> pmr = (Map<String, Object>) s.get("priceMatchRule");
                    if (pmr != null) {
                        ImportTemplatePriceMatchRule rule = new ImportTemplatePriceMatchRule();
                        rule.setTemplateId(templateId);
                        rule.setSheetConfigId(sheetId);
                        rule.setMatchFields(toJsonString(pmr.get("matchFields")));
                        rule.setWallThicknessMatchMode(toInt(pmr.get("wallThicknessMatchMode"), 0));
                        rule.setSpecRangeMatchMode(toInt(pmr.get("specRangeMatchMode"), 0));
                        rule.setSpecRangeConfig(toJsonString(pmr.get("specRangeConfig")));
                        priceMatchRuleMapper.insert(rule);
                    }
                }
            }

            @SuppressWarnings("unchecked")
            List<Map<String, Object>> charRules = (List<Map<String, Object>>) dto.get("charRules");
            if (charRules != null) {
                for (Map<String, Object> cr : charRules) {
                    ImportTemplateCharRule rule = new ImportTemplateCharRule();
                    rule.setTemplateId(templateId);
                    rule.setApplyFieldCodes(toJsonString(cr.get("applyFieldCodes")));
                    rule.setPresetRuleId(toLong(cr.get("presetRuleId")));
                    rule.setMatchType((String) cr.get("matchType"));
                    rule.setMatchPattern((String) cr.get("matchPattern"));
                    rule.setReplaceValue((String) cr.get("replaceValue"));
                    rule.setDescription((String) cr.get("description"));
                    rule.setSortOrder(toInt(cr.get("sortOrder"), 0));
                    rule.setEnabled(toInt(cr.get("enabled"), 1));
                    charRuleMapper.insert(rule);
                }
            }

            return Result.ok(Map.of("id", templateId));
        } catch (Exception e) {
            return Result.fail("更新模板失败: " + e.getMessage());
        }
    }

    @GetMapping("/{id}")
    public Result<?> getById(@PathVariable Long id) {
        try {
            ImportTemplate template = templateMapper.selectById(id);
            if (template == null) {
                return Result.fail("模板不存在");
            }

            Map<String, Object> result = new LinkedHashMap<>();
            result.put("template", template);

            List<ImportTemplateSheet> sheets = sheetMapper.selectList(
                    new LambdaQueryWrapper<ImportTemplateSheet>().eq(ImportTemplateSheet::getTemplateId, id));
            List<Map<String, Object>> sheetList = new ArrayList<>();
            for (ImportTemplateSheet sheet : sheets) {
                Map<String, Object> sheetMap = new LinkedHashMap<>();
                sheetMap.put("sheet", sheet);
                sheetMap.put("groups", groupMapper.selectList(
                        new LambdaQueryWrapper<ImportTemplateGroup>().eq(ImportTemplateGroup::getSheetConfigId, sheet.getId())));
                sheetMap.put("fields", fieldMapper.selectList(
                        new LambdaQueryWrapper<ImportTemplateField>().eq(ImportTemplateField::getSheetConfigId, sheet.getId())));
                sheetMap.put("valueMappings", valueMappingMapper.selectList(
                        new LambdaQueryWrapper<ImportTemplateFieldValueMapping>().eq(ImportTemplateFieldValueMapping::getSheetConfigId, sheet.getId())));
                sheetMap.put("priceMatchRules", priceMatchRuleMapper.selectList(
                        new LambdaQueryWrapper<ImportTemplatePriceMatchRule>().eq(ImportTemplatePriceMatchRule::getSheetConfigId, sheet.getId())));
                sheetList.add(sheetMap);
            }
            result.put("sheets", sheetList);
            result.put("charRules", charRuleMapper.selectList(
                    new LambdaQueryWrapper<ImportTemplateCharRule>().eq(ImportTemplateCharRule::getTemplateId, id)));

            return Result.ok(result);
        } catch (Exception e) {
            log.error("查询模板失败", e);
            return Result.fail("查询模板失败，请稍后重试");
        }
    }

    @GetMapping("/list")
    public Result<?> list(@RequestParam(required = false) Long supplierId) {
        try {
            LambdaQueryWrapper<ImportTemplate> wrapper = new LambdaQueryWrapper<>();
            if (supplierId != null) {
                wrapper.eq(ImportTemplate::getSupplierId, supplierId);
            }
            wrapper.orderByDesc(ImportTemplate::getId);
            return Result.ok(templateMapper.selectList(wrapper));
        } catch (Exception e) {
            log.error("查询模板列表失败", e);
            return Result.fail("查询模板列表失败，请稍后重试");
        }
    }

    @DeleteMapping("/{id}")
    @Transactional(rollbackFor = Exception.class)
    public Result<?> delete(@PathVariable Long id) {
        try {
            templateMapper.deleteById(id);
            sheetMapper.delete(new LambdaQueryWrapper<ImportTemplateSheet>().eq(ImportTemplateSheet::getTemplateId, id));
            groupMapper.delete(new LambdaQueryWrapper<ImportTemplateGroup>().eq(ImportTemplateGroup::getTemplateId, id));
            fieldMapper.delete(new LambdaQueryWrapper<ImportTemplateField>().eq(ImportTemplateField::getTemplateId, id));
            valueMappingMapper.delete(new LambdaQueryWrapper<ImportTemplateFieldValueMapping>().eq(ImportTemplateFieldValueMapping::getTemplateId, id));
            priceMatchRuleMapper.delete(new LambdaQueryWrapper<ImportTemplatePriceMatchRule>().eq(ImportTemplatePriceMatchRule::getTemplateId, id));
            charRuleMapper.delete(new LambdaQueryWrapper<ImportTemplateCharRule>().eq(ImportTemplateCharRule::getTemplateId, id));
            return Result.ok("删除成功");
        } catch (Exception e) {
            log.error("删除模板失败", e);
            return Result.fail("删除模板失败，请稍后重试");
        }
    }

    @SuppressWarnings("unchecked")
    private void saveFields(Long templateId, Long sheetId, Long groupId, Map<String, Object> parent) {
        List<Map<String, Object>> fields = (List<Map<String, Object>>) parent.get("fields");
        if (fields == null) return;
        for (Map<String, Object> f : fields) {
            ImportTemplateField field = new ImportTemplateField();
            field.setTemplateId(templateId);
            field.setSheetConfigId(sheetId);
            field.setGroupId(groupId);
            field.setFieldCode((String) f.get("fieldCode"));
            field.setFieldName((String) f.get("fieldName"));
            field.setSourceType((String) f.get("sourceType"));
            field.setSourceConfig(toJsonString(f.get("sourceConfig")));
            field.setTransformConfig(toJsonString(f.get("transformConfig")));
            field.setRequired(toInt(f.get("required"), 0));
            field.setDefaultValue((String) f.get("defaultValue"));
            field.setSortOrder(toInt(f.get("sortOrder"), 0));
            fieldMapper.insert(field);
        }
    }

    @SuppressWarnings("unchecked")
    private void saveValueMappings(Long templateId, Long sheetId, Long groupId, Map<String, Object> parent) {
        List<Map<String, Object>> mappings = (List<Map<String, Object>>) parent.get("valueMappings");
        if (mappings == null) return;
        for (Map<String, Object> m : mappings) {
            ImportTemplateFieldValueMapping vm = new ImportTemplateFieldValueMapping();
            vm.setTemplateId(templateId);
            vm.setSheetConfigId(sheetId);
            vm.setGroupId(groupId);
            vm.setTargetField((String) m.get("targetField"));
            vm.setSourceValue((String) m.get("sourceValue"));
            vm.setQualifier((String) m.get("qualifier"));
            vm.setTargetValue((String) m.get("targetValue"));
            vm.setSortOrder(toInt(m.get("sortOrder"), 0));
            valueMappingMapper.insert(vm);
        }
    }

    private static Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        try { return Long.parseLong(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static int toInt(Object val, int defaultVal) {
        if (val == null) return defaultVal;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return defaultVal; }
    }

    private static Integer toIntOrNull(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.intValue();
        try { return Integer.parseInt(val.toString()); } catch (NumberFormatException e) { return null; }
    }

    private static String toJsonString(Object val) {
        if (val == null) return null;
        if (val instanceof String s) return s;
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(val);
        } catch (Exception e) {
            return val.toString();
        }
    }
}
