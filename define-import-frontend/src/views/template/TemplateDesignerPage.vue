<script setup>
import { ref, reactive, computed, onMounted, watch, nextTick } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { templateApi, previewApi } from '@/api/index.js'
import { ElMessage } from 'element-plus'
import {
  ArrowLeft, Plus, Delete, Upload, Setting, Document
} from '@element-plus/icons-vue'

const route = useRoute()
const router = useRouter()
const isEdit = computed(() => !!route.params.id)
const saving = ref(false)
const formRef = ref(null)

// ─── Field definitions ──────────────────────────────────────
const fieldOptions = [
  { value: 'category', label: '品名/品类' },
  { value: 'spec', label: '规格' },
  { value: 'origin', label: '产地' },
  { value: 'material', label: '材质' },
  { value: 'package_num', label: '件数' },
  { value: 'whole_num', label: '整支数' },
  { value: 'odd_num', label: '零头数' },
  { value: 'weight', label: '重量' },
  { value: 'price', label: '价格' },
  { value: 'remark', label: '备注' }
]

const sourceTypeOptions = [
  { value: 'COLUMN', label: '列映射' },
  { value: 'FIXED_CELL', label: '固定单元格' },
  { value: 'FIXED_VALUE', label: '固定值' },
  { value: 'COMPOSITE', label: '组合' },
  { value: 'COLUMN_HEADER', label: '列头' }
]

const valueMappingFieldOptions = [
  { value: 'category', label: '品名/品类' },
  { value: 'origin', label: '产地' },
  { value: 'material', label: '材质' },
  { value: 'remark', label: '备注' }
]

// ─── Excel preview state ────────────────────────────────────
const fileId = ref(null)
const sheets = ref([])
const activeSheetIndex = ref(0)
const sheetData = ref({ headers: [], rows: [] })
const previewLoading = ref(false)
const selectedCell = reactive({ row: null, col: null })

// ─── Template config state ──────────────────────────────────
const templateConfig = reactive({
  templateCode: '',
  templateName: '',
  supplierId: null,
  supplierName: '',
  remark: '',
  sheetConfigs: []
})

const formRules = {
  templateCode: [{ required: true, message: '请输入模板编号', trigger: 'blur' }],
  templateName: [{ required: true, message: '请输入模板名称', trigger: 'blur' }]
}

// ─── Excel upload & preview ─────────────────────────────────
const handleUpload = async (uploadFile) => {
  previewLoading.value = true
  try {
    const { data } = await previewApi.upload(uploadFile.file || uploadFile.raw)
    const result = data.data || data
    fileId.value = result.fileId
    sheets.value = result.sheets || []

    templateConfig.sheetConfigs = sheets.value.map((s, idx) => ({
      sheetIndex: idx,
      sheetName: s.name || s.sheetName || `Sheet${idx + 1}`,
      contentType: 'INVENTORY',
      headerRowIndex: 0,
      dataStartRow: 1,
      dataEndRow: null,
      enableMergedCell: false,
      dataGroups: [createDefaultGroup()]
    }))

    activeSheetIndex.value = 0
    await loadSheetData(0)
  } catch (e) {
    ElMessage.error('Excel上传失败: ' + (e.response?.data?.message || e.message))
  } finally {
    previewLoading.value = false
  }
}

const loadSheetData = async (sheetIndex) => {
  if (!fileId.value) return
  previewLoading.value = true
  try {
    const sheetCfg = templateConfig.sheetConfigs[sheetIndex]
    const headerRow = sheetCfg ? sheetCfg.headerRowIndex : 0
    const { data } = await previewApi.getSheetData(fileId.value, sheetIndex, headerRow)
    const result = data.data || data
    sheetData.value = {
      headers: result.headers || [],
      rows: result.rows || []
    }
  } catch (e) {
    ElMessage.error('加载Sheet数据失败')
    sheetData.value = { headers: [], rows: [] }
  } finally {
    previewLoading.value = false
  }
}

const switchSheet = (idx) => {
  activeSheetIndex.value = idx
  loadSheetData(idx)
}

// Transform rows into objects for el-table
const tableData = computed(() => {
  return sheetData.value.rows.map((row, rowIdx) => {
    const obj = { _rowIndex: rowIdx }
    if (Array.isArray(row)) {
      row.forEach((val, colIdx) => { obj[`col_${colIdx}`] = val })
    } else {
      Object.assign(obj, row)
    }
    return obj
  })
})

const tableColumns = computed(() => {
  return sheetData.value.headers.map((h, idx) => ({
    label: h || `列${idx}`,
    prop: `col_${idx}`,
    colIndex: idx
  }))
})

const handleCellClick = (rowIdx, colIdx) => {
  selectedCell.row = rowIdx
  selectedCell.col = colIdx
}

const isCellSelected = (rowIdx, colIdx) => {
  return selectedCell.row === rowIdx && selectedCell.col === colIdx
}

// ─── Current sheet config (convenience) ─────────────────────
const currentSheetConfig = computed(() => {
  return templateConfig.sheetConfigs[activeSheetIndex.value] || null
})

// ─── Group & field CRUD ─────────────────────────────────────
function createDefaultGroup() {
  return {
    groupName: '默认组',
    fixedCategory: '',
    fixedOrigin: '',
    fixedMaterial: '',
    fixedRemark: '',
    dataStartRow: null,
    dataEndRow: null,
    fieldMappings: createDefaultFieldMappings(),
    fieldValueMappings: []
  }
}

function createDefaultFieldMappings() {
  return fieldOptions.map(f => ({
    fieldName: f.value,
    sourceType: 'COLUMN',
    columnIndex: null,
    headerAliases: [],
    cellRef: '',
    fixedValue: '',
    required: ['category', 'spec', 'weight'].includes(f.value),
    transform: null
  }))
}

function createEmptyFieldMapping() {
  return {
    fieldName: '',
    sourceType: 'COLUMN',
    columnIndex: null,
    headerAliases: [],
    cellRef: '',
    fixedValue: '',
    required: false,
    transform: null
  }
}

function createEmptyValueMapping() {
  return {
    targetField: 'category',
    rawValue: '',
    qualifier: '',
    mappedValue: ''
  }
}

const addGroup = (sheetIdx) => {
  const cfg = templateConfig.sheetConfigs[sheetIdx]
  if (!cfg) return
  cfg.dataGroups.push({
    ...createDefaultGroup(),
    groupName: `数据组${cfg.dataGroups.length + 1}`
  })
}

const removeGroup = (sheetIdx, groupIdx) => {
  const cfg = templateConfig.sheetConfigs[sheetIdx]
  if (!cfg || cfg.dataGroups.length <= 1) {
    ElMessage.warning('至少保留一个数据组')
    return
  }
  cfg.dataGroups.splice(groupIdx, 1)
}

const addFieldMapping = (group) => {
  group.fieldMappings.push(createEmptyFieldMapping())
}

const removeFieldMapping = (group, idx) => {
  group.fieldMappings.splice(idx, 1)
}

const addValueMapping = (group) => {
  group.fieldValueMappings.push(createEmptyValueMapping())
}

const removeValueMapping = (group, idx) => {
  group.fieldValueMappings.splice(idx, 1)
}

// ─── Alias tag input helpers ────────────────────────────────
const aliasInputVisible = ref({})
const aliasInputValue = ref('')

const showAliasInput = (key) => {
  aliasInputVisible.value[key] = true
  aliasInputValue.value = ''
}

const handleAliasConfirm = (mapping, key) => {
  const val = aliasInputValue.value.trim()
  if (val && !mapping.headerAliases.includes(val)) {
    mapping.headerAliases.push(val)
  }
  aliasInputVisible.value[key] = false
  aliasInputValue.value = ''
}

const removeAlias = (mapping, idx) => {
  mapping.headerAliases.splice(idx, 1)
}

// ─── Save template ──────────────────────────────────────────
const handleSave = async () => {
  if (formRef.value) {
    try {
      await formRef.value.validate()
    } catch {
      ElMessage.warning('请完善必填项')
      return
    }
  }

  saving.value = true
  try {
    const payload = {
      ...toPlainObject(templateConfig)
    }
    if (isEdit.value) {
      payload.id = Number(route.params.id)
    }
    await templateApi.create(payload)
    ElMessage.success(isEdit.value ? '模板更新成功' : '模板创建成功')
    router.push('/template')
  } catch (e) {
    ElMessage.error('保存失败: ' + (e.response?.data?.message || e.message))
  } finally {
    saving.value = false
  }
}

function toPlainObject(obj) {
  return JSON.parse(JSON.stringify(obj))
}

// ─── Load existing template (edit mode) ─────────────────────
const loadTemplate = async (id) => {
  try {
    const { data } = await templateApi.getById(id)
    const tpl = data.data || data
    templateConfig.templateCode = tpl.templateCode || ''
    templateConfig.templateName = tpl.templateName || ''
    templateConfig.supplierId = tpl.supplierId || null
    templateConfig.supplierName = tpl.supplierName || ''
    templateConfig.remark = tpl.remark || ''
    if (tpl.sheetConfigs && tpl.sheetConfigs.length) {
      templateConfig.sheetConfigs = tpl.sheetConfigs.map(sc => ({
        sheetIndex: sc.sheetIndex ?? 0,
        sheetName: sc.sheetName || 'Sheet1',
        contentType: sc.contentType || 'INVENTORY',
        headerRowIndex: sc.headerRowIndex ?? 0,
        dataStartRow: sc.dataStartRow ?? 1,
        dataEndRow: sc.dataEndRow ?? null,
        enableMergedCell: sc.enableMergedCell ?? false,
        dataGroups: (sc.dataGroups || []).map(g => ({
          groupName: g.groupName || '默认组',
          fixedCategory: g.fixedCategory || '',
          fixedOrigin: g.fixedOrigin || '',
          fixedMaterial: g.fixedMaterial || '',
          fixedRemark: g.fixedRemark || '',
          dataStartRow: g.dataStartRow ?? null,
          dataEndRow: g.dataEndRow ?? null,
          fieldMappings: g.fieldMappings || createDefaultFieldMappings(),
          fieldValueMappings: g.fieldValueMappings || []
        }))
      }))
      sheets.value = templateConfig.sheetConfigs.map((sc, i) => ({
        index: i,
        name: sc.sheetName
      }))
    }
  } catch (e) {
    ElMessage.error('加载模板失败')
  }
}

onMounted(() => {
  if (isEdit.value) {
    loadTemplate(route.params.id)
  }
})

const getFieldLabel = (val) => {
  const f = fieldOptions.find(o => o.value === val)
  return f ? f.label : val
}

const activeGroupCollapses = ref({})

const initCollapseState = (sheetIdx, groupCount) => {
  if (!activeGroupCollapses.value[sheetIdx]) {
    activeGroupCollapses.value[sheetIdx] = Array.from({ length: groupCount }, (_, i) => i)
  }
}
</script>

<template>
  <div class="designer-page">
    <!-- ─── Top bar ──────────────────────────────────── -->
    <div class="designer-header">
      <div class="header-left">
        <el-button text @click="$router.push('/template')">
          <el-icon><ArrowLeft /></el-icon> 返回列表
        </el-button>
        <el-divider direction="vertical" />
        <span class="header-title">模板设计器</span>
        <el-tag v-if="isEdit" type="info" size="small" class="edit-tag">编辑模式</el-tag>
      </div>
      <el-button type="primary" @click="handleSave" :loading="saving">
        <el-icon><Document /></el-icon> 保存模板
      </el-button>
    </div>

    <!-- ─── Main content ─────────────────────────────── -->
    <div class="designer-body">
      <!-- ═══ Left panel: Excel preview ═══ -->
      <div class="panel-left">
        <div class="panel-section">
          <div class="upload-area">
            <el-upload
              :auto-upload="false"
              :show-file-list="false"
              accept=".xlsx,.xls"
              :on-change="handleUpload"
            >
              <el-button type="primary" plain>
                <el-icon><Upload /></el-icon> 上传Excel文件
              </el-button>
            </el-upload>
            <span v-if="fileId" class="upload-hint">文件已上传，可切换Sheet查看</span>
          </div>

          <!-- Sheet tabs -->
          <div v-if="sheets.length" class="sheet-tabs">
            <el-radio-group v-model="activeSheetIndex" size="small" @change="switchSheet">
              <el-radio-button
                v-for="(s, idx) in sheets"
                :key="idx"
                :value="idx"
              >
                {{ s.name || s.sheetName || `Sheet${idx + 1}` }}
              </el-radio-button>
            </el-radio-group>
          </div>

          <!-- Excel data table -->
          <div class="excel-table-wrap" v-loading="previewLoading">
            <el-table
              v-if="tableData.length"
              :data="tableData"
              border
              size="small"
              max-height="calc(100vh - 280px)"
              :cell-class-name="({ rowIndex, columnIndex }) =>
                isCellSelected(rowIndex, columnIndex - 1) ? 'cell-selected' : ''"
              style="width: 100%"
            >
              <el-table-column type="index" label="#" width="50" align="center" fixed />
              <el-table-column
                v-for="col in tableColumns"
                :key="col.colIndex"
                :prop="col.prop"
                :label="`${col.label} [${col.colIndex}]`"
                min-width="120"
                show-overflow-tooltip
              >
                <template #default="{ row, $index }">
                  <span
                    class="cell-content"
                    @click="handleCellClick($index, col.colIndex)"
                  >{{ row[col.prop] ?? '' }}</span>
                </template>
              </el-table-column>
            </el-table>
            <el-empty v-else description="请上传Excel文件以预览内容" />
          </div>
        </div>
      </div>

      <!-- ═══ Right panel: Config ═══ -->
      <div class="panel-right">
        <div class="config-scroll">
          <!-- ── Basic info ── -->
          <el-card shadow="never" class="config-card">
            <template #header>
              <div class="config-card-header">
                <el-icon><Setting /></el-icon>
                <span>模板基础信息</span>
              </div>
            </template>
            <el-form
              ref="formRef"
              :model="templateConfig"
              :rules="formRules"
              label-width="100px"
              label-position="right"
              size="default"
            >
              <el-row :gutter="16">
                <el-col :span="12">
                  <el-form-item label="模板编号" prop="templateCode">
                    <el-input v-model="templateConfig.templateCode" placeholder="如: TPL_SUPPLIER_001" />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="模板名称" prop="templateName">
                    <el-input v-model="templateConfig.templateName" placeholder="如: XX供应商库存模板" />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-row :gutter="16">
                <el-col :span="12">
                  <el-form-item label="供应商ID">
                    <el-input-number
                      v-model="templateConfig.supplierId"
                      :min="0"
                      controls-position="right"
                      placeholder="供应商ID"
                      style="width: 100%"
                    />
                  </el-form-item>
                </el-col>
                <el-col :span="12">
                  <el-form-item label="供应商名称">
                    <el-input v-model="templateConfig.supplierName" placeholder="供应商名称" />
                  </el-form-item>
                </el-col>
              </el-row>
              <el-form-item label="备注">
                <el-input
                  v-model="templateConfig.remark"
                  type="textarea"
                  :rows="2"
                  placeholder="模板说明..."
                />
              </el-form-item>
            </el-form>
          </el-card>

          <!-- ── Sheet configs ── -->
          <template v-if="templateConfig.sheetConfigs.length">
            <el-card
              v-for="(sheetCfg, sheetIdx) in templateConfig.sheetConfigs"
              :key="sheetIdx"
              shadow="never"
              class="config-card"
            >
              <template #header>
                <div class="config-card-header">
                  <el-icon><Document /></el-icon>
                  <span>Sheet配置: {{ sheetCfg.sheetName }}</span>
                  <el-tag size="small" :type="sheetCfg.contentType === 'PRICE' ? 'warning' : 'primary'" class="sheet-type-tag">
                    {{ sheetCfg.contentType === 'PRICE' ? '价格' : '库存' }}
                  </el-tag>
                </div>
              </template>

              <el-form label-width="120px" label-position="right" size="default">
                <el-row :gutter="16">
                  <el-col :span="12">
                    <el-form-item label="内容类型">
                      <el-radio-group v-model="sheetCfg.contentType">
                        <el-radio value="INVENTORY">库存</el-radio>
                        <el-radio value="PRICE">价格</el-radio>
                      </el-radio-group>
                    </el-form-item>
                  </el-col>
                  <el-col :span="12">
                    <el-form-item label="合并单元格">
                      <el-switch v-model="sheetCfg.enableMergedCell" />
                    </el-form-item>
                  </el-col>
                </el-row>
                <el-row :gutter="16">
                  <el-col :span="8">
                    <el-form-item label="表头所在行">
                      <el-input-number
                        v-model="sheetCfg.headerRowIndex"
                        :min="0"
                        controls-position="right"
                        style="width: 100%"
                      />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="数据起始行">
                      <el-input-number
                        v-model="sheetCfg.dataStartRow"
                        :min="0"
                        controls-position="right"
                        style="width: 100%"
                      />
                    </el-form-item>
                  </el-col>
                  <el-col :span="8">
                    <el-form-item label="数据结束行">
                      <el-input-number
                        v-model="sheetCfg.dataEndRow"
                        :min="0"
                        controls-position="right"
                        placeholder="留空为最后"
                        style="width: 100%"
                      />
                    </el-form-item>
                  </el-col>
                </el-row>
              </el-form>

              <!-- ── Data groups ── -->
              <div class="groups-section">
                <div class="section-header">
                  <span class="section-title">数据组配置</span>
                  <el-button type="primary" size="small" plain @click="addGroup(sheetIdx)">
                    <el-icon><Plus /></el-icon> 新增组
                  </el-button>
                </div>

                {{ void initCollapseState(sheetIdx, sheetCfg.dataGroups.length) }}
                <el-collapse v-model="activeGroupCollapses[sheetIdx]">
                  <el-collapse-item
                    v-for="(group, groupIdx) in sheetCfg.dataGroups"
                    :key="groupIdx"
                    :name="groupIdx"
                  >
                    <template #title>
                      <div class="group-title">
                        <span>{{ group.groupName || `数据组${groupIdx + 1}` }}</span>
                        <el-tag size="small" type="info">{{ group.fieldMappings.length }}个字段</el-tag>
                      </div>
                    </template>

                    <div class="group-content">
                      <!-- Group basic config -->
                      <el-form label-width="100px" label-position="right" size="small">
                        <el-row :gutter="12">
                          <el-col :span="8">
                            <el-form-item label="组名称">
                              <el-input v-model="group.groupName" placeholder="数据组名称" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="8">
                            <el-form-item label="起始行">
                              <el-input-number
                                v-model="group.dataStartRow"
                                :min="0"
                                controls-position="right"
                                placeholder="可选"
                                style="width: 100%"
                              />
                            </el-form-item>
                          </el-col>
                          <el-col :span="8">
                            <el-form-item label="结束行">
                              <el-input-number
                                v-model="group.dataEndRow"
                                :min="0"
                                controls-position="right"
                                placeholder="可选"
                                style="width: 100%"
                              />
                            </el-form-item>
                          </el-col>
                        </el-row>
                        <el-row :gutter="12">
                          <el-col :span="6">
                            <el-form-item label="固定品类">
                              <el-input v-model="group.fixedCategory" placeholder="可选" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="6">
                            <el-form-item label="固定产地">
                              <el-input v-model="group.fixedOrigin" placeholder="可选" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="6">
                            <el-form-item label="固定材质">
                              <el-input v-model="group.fixedMaterial" placeholder="可选" />
                            </el-form-item>
                          </el-col>
                          <el-col :span="6">
                            <el-form-item label="固定备注">
                              <el-input v-model="group.fixedRemark" placeholder="可选" />
                            </el-form-item>
                          </el-col>
                        </el-row>
                      </el-form>

                      <!-- ── Field mappings table ── -->
                      <div class="sub-section">
                        <div class="sub-section-header">
                          <span>字段映射</span>
                          <el-button size="small" text type="primary" @click="addFieldMapping(group)">
                            <el-icon><Plus /></el-icon> 添加字段
                          </el-button>
                        </div>
                        <el-table :data="group.fieldMappings" border size="small" class="mapping-table">
                          <el-table-column label="字段" width="130">
                            <template #default="{ row }">
                              <el-select v-model="row.fieldName" placeholder="选择字段" size="small" filterable allow-create>
                                <el-option
                                  v-for="f in fieldOptions"
                                  :key="f.value"
                                  :label="f.label"
                                  :value="f.value"
                                />
                              </el-select>
                            </template>
                          </el-table-column>
                          <el-table-column label="来源类型" width="120">
                            <template #default="{ row }">
                              <el-select v-model="row.sourceType" size="small">
                                <el-option
                                  v-for="s in sourceTypeOptions"
                                  :key="s.value"
                                  :label="s.label"
                                  :value="s.value"
                                />
                              </el-select>
                            </template>
                          </el-table-column>
                          <el-table-column label="来源配置" min-width="240">
                            <template #default="{ row, $index }">
                              <!-- COLUMN -->
                              <div v-if="row.sourceType === 'COLUMN'" class="source-config">
                                <div class="config-row">
                                  <span class="config-label">列号:</span>
                                  <el-input-number
                                    v-model="row.columnIndex"
                                    :min="0"
                                    size="small"
                                    controls-position="right"
                                    style="width: 100px"
                                  />
                                </div>
                                <div class="config-row">
                                  <span class="config-label">别名:</span>
                                  <div class="alias-tags">
                                    <el-tag
                                      v-for="(alias, aIdx) in row.headerAliases"
                                      :key="aIdx"
                                      closable
                                      size="small"
                                      @close="removeAlias(row, aIdx)"
                                    >{{ alias }}</el-tag>
                                    <el-input
                                      v-if="aliasInputVisible[`${$index}`]"
                                      v-model="aliasInputValue"
                                      size="small"
                                      style="width: 80px"
                                      @keyup.enter="handleAliasConfirm(row, `${$index}`)"
                                      @blur="handleAliasConfirm(row, `${$index}`)"
                                    />
                                    <el-button
                                      v-else
                                      size="small"
                                      text
                                      type="primary"
                                      @click="showAliasInput(`${$index}`)"
                                    >+ 别名</el-button>
                                  </div>
                                </div>
                              </div>
                              <!-- FIXED_CELL -->
                              <div v-else-if="row.sourceType === 'FIXED_CELL'" class="source-config">
                                <span class="config-label">单元格:</span>
                                <el-input
                                  v-model="row.cellRef"
                                  size="small"
                                  placeholder="如 A1"
                                  style="width: 100px"
                                />
                              </div>
                              <!-- FIXED_VALUE -->
                              <div v-else-if="row.sourceType === 'FIXED_VALUE'" class="source-config">
                                <span class="config-label">固定值:</span>
                                <el-input
                                  v-model="row.fixedValue"
                                  size="small"
                                  placeholder="输入固定值"
                                  style="width: 160px"
                                />
                              </div>
                              <!-- COLUMN_HEADER -->
                              <div v-else-if="row.sourceType === 'COLUMN_HEADER'" class="source-config">
                                <span class="config-label">列号:</span>
                                <el-input-number
                                  v-model="row.columnIndex"
                                  :min="0"
                                  size="small"
                                  controls-position="right"
                                  style="width: 100px"
                                />
                              </div>
                              <!-- COMPOSITE -->
                              <div v-else class="source-config">
                                <el-input
                                  v-model="row.fixedValue"
                                  size="small"
                                  placeholder="组合表达式"
                                  style="width: 200px"
                                />
                              </div>
                            </template>
                          </el-table-column>
                          <el-table-column label="必填" width="60" align="center">
                            <template #default="{ row }">
                              <el-checkbox v-model="row.required" />
                            </template>
                          </el-table-column>
                          <el-table-column label="操作" width="60" align="center">
                            <template #default="{ $index }">
                              <el-button
                                link
                                type="danger"
                                size="small"
                                @click="removeFieldMapping(group, $index)"
                              >
                                <el-icon><Delete /></el-icon>
                              </el-button>
                            </template>
                          </el-table-column>
                        </el-table>
                      </div>

                      <!-- ── Value mappings table ── -->
                      <div class="sub-section">
                        <div class="sub-section-header">
                          <span>字段值映射</span>
                          <el-button size="small" text type="primary" @click="addValueMapping(group)">
                            <el-icon><Plus /></el-icon> 添加映射
                          </el-button>
                        </div>
                        <el-table
                          v-if="group.fieldValueMappings.length"
                          :data="group.fieldValueMappings"
                          border
                          size="small"
                          class="mapping-table"
                        >
                          <el-table-column label="目标字段" width="130">
                            <template #default="{ row }">
                              <el-select v-model="row.targetField" size="small">
                                <el-option
                                  v-for="f in valueMappingFieldOptions"
                                  :key="f.value"
                                  :label="f.label"
                                  :value="f.value"
                                />
                              </el-select>
                            </template>
                          </el-table-column>
                          <el-table-column label="原始值" min-width="120">
                            <template #default="{ row }">
                              <el-input v-model="row.rawValue" size="small" placeholder="Excel中的原始值" />
                            </template>
                          </el-table-column>
                          <el-table-column label="限定词" width="120">
                            <template #default="{ row }">
                              <el-input v-model="row.qualifier" size="small" placeholder="可选" />
                            </template>
                          </el-table-column>
                          <el-table-column label="目标值" min-width="120">
                            <template #default="{ row }">
                              <el-input v-model="row.mappedValue" size="small" placeholder="映射后的值" />
                            </template>
                          </el-table-column>
                          <el-table-column label="操作" width="60" align="center">
                            <template #default="{ $index }">
                              <el-button
                                link
                                type="danger"
                                size="small"
                                @click="removeValueMapping(group, $index)"
                              >
                                <el-icon><Delete /></el-icon>
                              </el-button>
                            </template>
                          </el-table-column>
                        </el-table>
                        <div v-else class="empty-hint">暂无字段值映射，点击上方按钮添加</div>
                      </div>

                      <!-- Remove group button -->
                      <div class="group-footer">
                        <el-popconfirm
                          title="确定删除该数据组？"
                          @confirm="removeGroup(sheetIdx, groupIdx)"
                        >
                          <template #reference>
                            <el-button type="danger" size="small" plain>
                              <el-icon><Delete /></el-icon> 删除该组
                            </el-button>
                          </template>
                        </el-popconfirm>
                      </div>
                    </div>
                  </el-collapse-item>
                </el-collapse>
              </div>
            </el-card>
          </template>

          <!-- No sheets hint -->
          <el-card v-if="!templateConfig.sheetConfigs.length" shadow="never" class="config-card">
            <el-empty description="请先上传Excel文件以配置Sheet映射规则" :image-size="80" />
          </el-card>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.designer-page {
  display: flex;
  flex-direction: column;
  height: calc(100vh - 200px);
  min-height: 500px;
  margin: -24px;
}

/* ─── Header ─── */
.designer-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  padding: 12px 20px;
  border-bottom: 1px solid #e4e7ed;
  background: #fafbfc;
  flex-shrink: 0;
}

.header-left {
  display: flex;
  align-items: center;
  gap: 8px;
}

.header-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.edit-tag {
  margin-left: 4px;
}

/* ─── Body: two-panel layout ─── */
.designer-body {
  display: flex;
  flex: 1;
  overflow: hidden;
}

.panel-left {
  width: 50%;
  min-width: 400px;
  border-right: 1px solid #e4e7ed;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-right {
  width: 50%;
  min-width: 400px;
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-section {
  display: flex;
  flex-direction: column;
  height: 100%;
  overflow: hidden;
}

.config-scroll {
  flex: 1;
  overflow-y: auto;
  padding: 16px;
}

/* ─── Upload area ─── */
.upload-area {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 12px 16px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

.upload-hint {
  font-size: 12px;
  color: #909399;
}

/* ─── Sheet tabs ─── */
.sheet-tabs {
  padding: 8px 16px;
  border-bottom: 1px solid #f0f0f0;
  flex-shrink: 0;
}

/* ─── Excel table ─── */
.excel-table-wrap {
  flex: 1;
  overflow: auto;
  padding: 8px;
}

.cell-content {
  cursor: pointer;
  display: block;
  padding: 2px 4px;
  border-radius: 2px;
}

.cell-content:hover {
  background: #ecf5ff;
}

:deep(.cell-selected) {
  background-color: #d9ecff !important;
}

/* ─── Config cards ─── */
.config-card {
  margin-bottom: 16px;
}

.config-card:last-child {
  margin-bottom: 0;
}

.config-card-header {
  display: flex;
  align-items: center;
  gap: 8px;
  font-weight: 600;
  font-size: 14px;
}

.sheet-type-tag {
  margin-left: auto;
}

/* ─── Groups section ─── */
.groups-section {
  margin-top: 8px;
}

.section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  padding-bottom: 8px;
  border-bottom: 1px solid #ebeef5;
}

.section-title {
  font-size: 14px;
  font-weight: 600;
  color: #303133;
}

.group-title {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 14px;
}

.group-content {
  padding: 8px 0;
}

.group-footer {
  display: flex;
  justify-content: flex-end;
  margin-top: 12px;
  padding-top: 8px;
  border-top: 1px dashed #ebeef5;
}

/* ─── Sub-sections (field mappings, value mappings) ─── */
.sub-section {
  margin-top: 16px;
}

.sub-section-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
  font-weight: 600;
  color: #606266;
}

.mapping-table {
  width: 100%;
}

/* ─── Source config inline layout ─── */
.source-config {
  display: flex;
  flex-wrap: wrap;
  align-items: flex-start;
  gap: 6px;
}

.config-row {
  display: flex;
  align-items: center;
  gap: 4px;
  width: 100%;
}

.config-label {
  font-size: 12px;
  color: #909399;
  white-space: nowrap;
  min-width: 40px;
}

.alias-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
  align-items: center;
}

.empty-hint {
  text-align: center;
  padding: 16px;
  font-size: 13px;
  color: #c0c4cc;
}
</style>
