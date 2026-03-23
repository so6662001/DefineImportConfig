<script setup>
import { ref, computed } from 'vue'
import { ElMessage } from 'element-plus'
import { previewApi } from '@/api/index.js'

const fileList = ref([])
const rawFile = ref(null)
const headerRow = ref(0)
const uploading = ref(false)
const sheetLoading = ref(false)

const fileId = ref(null)
const fileName = ref('')
const sheets = ref([])
const activeSheetIndex = ref(0)

const sheetData = ref(null)
const selectedCell = ref(null)

function handleFileChange(uploadFile) {
  rawFile.value = uploadFile.raw
}

function handleFileRemove() {
  rawFile.value = null
}

async function uploadAndParse() {
  if (!rawFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  uploading.value = true
  sheetData.value = null
  selectedCell.value = null
  try {
    const res = await previewApi.upload(rawFile.value)
    const body = res?.data
    if (body?.code === 200) {
      const data = body.data
      fileId.value = data.fileId
      fileName.value = data.fileName || rawFile.value.name
      sheets.value = data.sheets || []
      activeSheetIndex.value = 0
      ElMessage.success('文件上传成功')
      if (sheets.value.length > 0) {
        await loadSheetData(0)
      }
    } else {
      ElMessage.error(body?.message || '文件上传失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '文件上传失败')
  } finally {
    uploading.value = false
  }
}

async function loadSheetData(index) {
  if (!fileId.value) return
  sheetLoading.value = true
  selectedCell.value = null
  try {
    const res = await previewApi.getSheetData(fileId.value, index, headerRow.value)
    const body = res?.data
    if (body?.code === 200) {
      sheetData.value = body.data
    } else {
      ElMessage.error(body?.message || '加载Sheet数据失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '加载Sheet数据失败')
  } finally {
    sheetLoading.value = false
  }
}

function onTabChange(index) {
  activeSheetIndex.value = Number(index)
  loadSheetData(Number(index))
}

function colLetter(index) {
  let s = ''
  let n = index
  while (n >= 0) {
    s = String.fromCharCode(65 + (n % 26)) + s
    n = Math.floor(n / 26) - 1
  }
  return s
}

const columns = computed(() => {
  if (!sheetData.value?.rows?.length) return []
  const maxCols = Math.max(...sheetData.value.rows.map(r => (r.cells || r).length))
  return Array.from({ length: maxCols }, (_, i) => ({
    letter: colLetter(i),
    index: i
  }))
})

const displayRows = computed(() => {
  if (!sheetData.value?.rows) return []
  return sheetData.value.rows.map((row, rowIdx) => {
    const cells = row.cells || row
    const mapped = {}
    columns.value.forEach((col, colIdx) => {
      mapped[col.letter] = cells[colIdx] ?? ''
    })
    mapped._rowIndex = rowIdx
    return mapped
  })
})

const totalRows = computed(() => sheetData.value?.totalRows ?? displayRows.value.length)

function onCellClick(row, column) {
  if (column.property === '_rowNum') return
  const colIdx = columns.value.findIndex(c => c.letter === column.property)
  if (colIdx < 0) return
  const rowIdx = row._rowIndex
  const cellRef = colLetter(colIdx) + (rowIdx + 1)
  selectedCell.value = {
    ref: cellRef,
    row: rowIdx,
    col: colIdx,
    value: row[column.property]
  }
}

function cellClassName({ column }) {
  if (!selectedCell.value || column.property === '_rowNum') return ''
  if (column.property === columns.value[selectedCell.value.col]?.letter) {
    return 'selected-col'
  }
  return ''
}

function rowClassName({ row }) {
  if (!selectedCell.value) return ''
  if (row._rowIndex === selectedCell.value.row) return 'selected-row'
  return ''
}
</script>

<template>
  <div class="excel-preview-page">
    <h2 class="page-title">Excel 文件预览</h2>

    <el-card class="toolbar-card" shadow="never">
      <div class="toolbar-row">
        <div class="toolbar-item">
          <span class="toolbar-label">选择文件：</span>
          <el-upload
            v-model:file-list="fileList"
            :auto-upload="false"
            :limit="1"
            accept=".xlsx,.xls"
            :on-change="handleFileChange"
            :on-remove="handleFileRemove"
          >
            <el-button type="default">
              <el-icon><Upload /></el-icon>
              选择文件
            </el-button>
          </el-upload>
        </div>
        <div class="toolbar-item">
          <span class="toolbar-label">表头行：</span>
          <el-input-number
            v-model="headerRow"
            :min="0"
            :max="20"
            :controls="true"
            style="width: 130px"
          />
        </div>
        <el-button
          type="primary"
          :loading="uploading"
          :disabled="!rawFile"
          @click="uploadAndParse"
        >
          <el-icon v-if="!uploading"><Upload /></el-icon>
          上传并解析
        </el-button>
      </div>
    </el-card>

    <template v-if="fileId">
      <div class="file-info">
        <el-icon><Document /></el-icon>
        <span>文件：<strong>{{ fileName }}</strong></span>
        <el-divider direction="vertical" />
        <span>共 <strong>{{ sheets.length }}</strong> 个Sheet</span>
      </div>

      <el-tabs
        v-model="activeSheetIndex"
        type="border-card"
        @tab-change="onTabChange"
      >
        <el-tab-pane
          v-for="(sheet, idx) in sheets"
          :key="idx"
          :label="'Sheet' + (idx + 1) + ': ' + (sheet.name || sheet)"
          :name="idx"
        />
      </el-tabs>

      <div v-loading="sheetLoading" class="sheet-table-wrapper">
        <el-table
          v-if="sheetData"
          :data="displayRows"
          stripe
          border
          max-height="520"
          :cell-class-name="cellClassName"
          :row-class-name="rowClassName"
          @cell-click="onCellClick"
          style="width: 100%"
          empty-text="该Sheet无数据"
        >
          <el-table-column label="#" width="60" fixed prop="_rowNum">
            <template #default="{ row }">
              {{ row._rowIndex + 1 }}
            </template>
          </el-table-column>
          <el-table-column
            v-for="col in columns"
            :key="col.letter"
            :prop="col.letter"
            :label="col.letter"
            min-width="100"
            show-overflow-tooltip
          />
        </el-table>

        <div v-if="sheetData" class="row-count-info">
          共 <strong>{{ totalRows }}</strong> 行数据
        </div>
      </div>

      <div v-if="selectedCell" class="cell-info-bar">
        <el-icon><Position /></el-icon>
        选中单元格：<strong>{{ selectedCell.ref }}</strong>
        （行{{ selectedCell.row }}，列{{ selectedCell.col }}）
        值：<strong>"{{ selectedCell.value }}"</strong>
      </div>
    </template>

    <div v-else-if="!uploading" class="empty-state">
      <el-empty description="请选择Excel文件后点击上传并解析" />
    </div>
  </div>
</template>

<style scoped>
.excel-preview-page {
  width: 100%;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 20px;
}

.toolbar-card {
  margin-bottom: 24px;
}

.toolbar-row {
  display: flex;
  align-items: center;
  gap: 24px;
  flex-wrap: wrap;
}

.toolbar-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.toolbar-label {
  font-size: 14px;
  color: #606266;
  white-space: nowrap;
}

.file-info {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 16px;
  background: #f5f7fa;
  border-radius: 6px;
  margin-bottom: 16px;
  font-size: 14px;
  color: #606266;
}

.sheet-table-wrapper {
  margin-top: 16px;
  min-height: 200px;
}

.row-count-info {
  margin-top: 10px;
  font-size: 13px;
  color: #909399;
  text-align: right;
}

.empty-state {
  margin-top: 60px;
}

:deep(.selected-col) {
  background-color: #ecf5ff !important;
}

:deep(.selected-row) td {
  background-color: #ecf5ff !important;
}

:deep(.selected-row) td.selected-col {
  background-color: #d9ecff !important;
}
</style>
