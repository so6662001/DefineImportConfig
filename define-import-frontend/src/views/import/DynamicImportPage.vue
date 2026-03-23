<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { templateApi, importApi } from '@/api/index.js'

const templates = ref([])
const selectedTemplateId = ref(null)
const fileList = ref([])
const rawFile = ref(null)
const loading = ref(false)
const result = ref(null)
const activeTab = ref('inventory')

onMounted(async () => {
  try {
    const res = await templateApi.list()
    const body = res?.data
    if (body?.code === 200) {
      templates.value = body.data || []
    } else {
      ElMessage.error(body?.message || '加载模板列表失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '加载模板列表失败')
  }
})

function handleFileChange(uploadFile) {
  rawFile.value = uploadFile.raw
}

function handleFileRemove() {
  rawFile.value = null
}

async function doImportPreview() {
  if (!selectedTemplateId.value) {
    ElMessage.warning('请先选择模板')
    return
  }
  if (!rawFile.value) {
    ElMessage.warning('请先选择文件')
    return
  }
  loading.value = true
  result.value = null
  try {
    const res = await importApi.preview(rawFile.value, selectedTemplateId.value)
    const body = res?.data
    if (body?.code === 200) {
      result.value = body.data
      ElMessage.success('导入预览完成')
    } else {
      ElMessage.error(body?.message || '导入预览失败')
    }
  } catch (e) {
    ElMessage.error(e.message || '导入预览失败')
  } finally {
    loading.value = false
  }
}

const stats = computed(() => {
  if (!result.value) return null
  const inv = result.value.inventoryRows || []
  const price = result.value.priceRows || []
  const errors = result.value.errors || []
  const successCount = inv.filter(r => !r.hasError).length + price.filter(r => !r.hasError).length
  const errorCount = errors.length
  const duplicateCount = errors.filter(e => e.errorType === 'DUPLICATE').length
  return {
    total: result.value.totalRows ?? (inv.length + price.length),
    inventory: inv.length,
    price: price.length,
    success: successCount,
    error: errorCount,
    duplicate: duplicateCount
  }
})

const inventoryRows = computed(() => result.value?.inventoryRows || [])
const priceRows = computed(() => result.value?.priceRows || [])
const errorRows = computed(() => result.value?.errors || [])

function errorLevelType(level) {
  if (level === 'ERROR') return 'danger'
  if (level === 'WARN') return 'warning'
  return 'info'
}
</script>

<template>
  <div class="dynamic-import-page">
    <h2 class="page-title">动态导入测试</h2>

    <el-card class="toolbar-card" shadow="never">
      <div class="toolbar-row">
        <div class="toolbar-item">
          <span class="toolbar-label">选择模板：</span>
          <el-select
            v-model="selectedTemplateId"
            placeholder="请选择模板"
            clearable
            style="width: 260px"
          >
            <el-option
              v-for="t in templates"
              :key="t.id"
              :label="t.templateName || t.templateCode"
              :value="t.id"
            />
          </el-select>
        </div>
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
        <el-button
          type="primary"
          :loading="loading"
          :disabled="!selectedTemplateId || !rawFile"
          @click="doImportPreview"
        >
          <el-icon v-if="!loading"><CaretRight /></el-icon>
          开始导入预览
        </el-button>
      </div>
    </el-card>

    <template v-if="result">
      <div class="section-title">导入结果</div>

      <div class="stat-cards">
        <div class="stat-card">
          <div class="label">总行数</div>
          <div class="value primary">{{ stats.total }}</div>
        </div>
        <div class="stat-card">
          <div class="label">库存行</div>
          <div class="value primary">{{ stats.inventory }}</div>
        </div>
        <div class="stat-card">
          <div class="label">价格行</div>
          <div class="value primary">{{ stats.price }}</div>
        </div>
        <div class="stat-card">
          <div class="label">成功</div>
          <div class="value success">{{ stats.success }}</div>
        </div>
        <div class="stat-card">
          <div class="label">错误</div>
          <div class="value danger">{{ stats.error }}</div>
        </div>
        <div class="stat-card">
          <div class="label">重复</div>
          <div class="value warning">{{ stats.duplicate }}</div>
        </div>
      </div>

      <el-tabs v-model="activeTab" type="border-card">
        <el-tab-pane label="库存数据" name="inventory">
          <el-table :data="inventoryRows" stripe border max-height="480" empty-text="暂无库存数据">
            <el-table-column prop="rowIndex" label="行号" width="70" fixed />
            <el-table-column prop="sheetName" label="Sheet" width="100" show-overflow-tooltip />
            <el-table-column prop="groupName" label="分组" width="100" show-overflow-tooltip />
            <el-table-column prop="category" label="品类" width="100" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格" width="100" show-overflow-tooltip />
            <el-table-column prop="origin" label="产地" width="100" show-overflow-tooltip />
            <el-table-column prop="material" label="材质" width="100" show-overflow-tooltip />
            <el-table-column prop="packageNum" label="件数" width="80" />
            <el-table-column prop="wholeNum" label="整支数" width="80" />
            <el-table-column prop="oddNum" label="零支数" width="80" />
            <el-table-column prop="weight" label="重量" width="90" />
            <el-table-column prop="price" label="价格" width="90" />
            <el-table-column prop="remark" label="备注" min-width="120" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="price">
          <template #label>
            价格数据
          </template>
          <el-table :data="priceRows" stripe border max-height="480" empty-text="暂无价格数据">
            <el-table-column prop="rowIndex" label="行号" width="70" fixed />
            <el-table-column prop="category" label="品类" width="120" show-overflow-tooltip />
            <el-table-column prop="spec" label="规格" width="120" show-overflow-tooltip />
            <el-table-column prop="origin" label="产地" width="120" show-overflow-tooltip />
            <el-table-column prop="material" label="材质" width="120" show-overflow-tooltip />
            <el-table-column prop="wallThickness" label="壁厚" width="100" />
            <el-table-column prop="price" label="价格" width="100" />
          </el-table>
        </el-tab-pane>

        <el-tab-pane name="error">
          <template #label>
            错误信息<el-badge v-if="errorRows.length" :value="errorRows.length" class="tab-badge" />
          </template>
          <el-table :data="errorRows" stripe border max-height="480" empty-text="暂无错误信息">
            <el-table-column prop="sheetName" label="Sheet" width="100" show-overflow-tooltip />
            <el-table-column prop="rowIndex" label="行号" width="70" />
            <el-table-column prop="fieldName" label="字段" width="120" show-overflow-tooltip />
            <el-table-column prop="errorMsg" label="错误信息" min-width="200" show-overflow-tooltip />
            <el-table-column prop="errorLevel" label="级别" width="90">
              <template #default="{ row }">
                <el-tag :type="errorLevelType(row.errorLevel)" size="small">
                  {{ row.errorLevel }}
                </el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="errorType" label="错误类型" width="120" show-overflow-tooltip />
            <el-table-column prop="duplicateOfRow" label="重复行" width="90" />
          </el-table>
        </el-tab-pane>
      </el-tabs>
    </template>

    <div v-else-if="!loading" class="empty-state">
      <el-empty description="请选择模板和文件后开始导入预览" />
    </div>
  </div>
</template>

<style scoped>
.dynamic-import-page {
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

.section-title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
  margin-bottom: 16px;
  padding-left: 10px;
  border-left: 3px solid #409eff;
}

.tab-badge {
  margin-left: 6px;
}

.empty-state {
  margin-top: 60px;
}
</style>
