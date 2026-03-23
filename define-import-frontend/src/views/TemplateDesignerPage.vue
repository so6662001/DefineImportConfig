<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useTemplateStore } from '../store/template'

const DRAFT_KEY = 'template-designer-draft'

const router = useRouter()
const templateStore = useTemplateStore()

const props = defineProps({
  id: {
    type: String,
    default: undefined
  }
})

const templateJson = ref('')
const isEditMode = computed(() => !!props.id)

const sampleTemplate = {
  templateCode: 'DEMO_TPL_001',
  templateName: '演示模板-基础库存',
  supplierName: '示例供应商',
  allSheetsPrice: 0,
  status: 1,
  sheets: [
    {
      sheetIndex: 0,
      sheetName: '库存',
      contentType: 1,
      headerRowIndex: 0,
      emptyRowThreshold: 2,
      enableMergeCell: 1,
      groups: [
        {
          groupSeq: 1,
          groupName: '默认组',
          fields: [
            {
              fieldCode: 'category',
              fieldName: '品类',
              sourceType: 'COLUMN',
              sourceConfig: { columnIndex: 0, matchMode: 'INDEX' },
              required: 1
            },
            {
              fieldCode: 'spec',
              fieldName: '规格',
              sourceType: 'COLUMN',
              sourceConfig: { columnIndex: 1, matchMode: 'INDEX' },
              required: 1
            },
            {
              fieldCode: 'origin',
              fieldName: '产地',
              sourceType: 'COLUMN',
              sourceConfig: { columnIndex: 2, matchMode: 'INDEX' }
            },
            {
              fieldCode: 'weight',
              fieldName: '重量',
              sourceType: 'COLUMN',
              sourceConfig: { columnIndex: 3, matchMode: 'INDEX' }
            },
            {
              fieldCode: 'price',
              fieldName: '价格',
              sourceType: 'COLUMN',
              sourceConfig: { columnIndex: 4, matchMode: 'INDEX' }
            }
          ]
        }
      ]
    }
  ]
}

function loadSample() {
  templateJson.value = JSON.stringify(sampleTemplate, null, 2)
}

async function loadDetail() {
  if (!props.id) return
  try {
    const data = await templateStore.fetchById(props.id)
    templateJson.value = JSON.stringify(data, null, 2)
  } catch (e) {
    ElMessage.error(e.message || '加载模板失败')
  }
}

async function saveTemplate() {
  try {
    const body = JSON.parse(templateJson.value)
    const result = await templateStore.createTemplate(body)
    ElMessage.success(`模板创建成功，ID：${result?.id ?? ''}`)
    router.push('/template')
  } catch (e) {
    if (e instanceof SyntaxError) {
      ElMessage.error('JSON 格式错误：' + e.message)
    } else {
      ElMessage.error(e.message || '保存失败')
    }
  }
}

function copyAsNew() {
  sessionStorage.setItem(DRAFT_KEY, templateJson.value)
  router.push('/template/designer')
}

onMounted(() => {
  const draft = sessionStorage.getItem(DRAFT_KEY)
  if (draft && !props.id) {
    templateJson.value = draft
    sessionStorage.removeItem(DRAFT_KEY)
    ElMessage.info('已载入复制的模板 JSON，请修改编号后保存')
    return
  }
  if (props.id) {
    loadDetail()
  }
})

watch(
  () => props.id,
  (newId) => {
    if (newId) loadDetail()
    else {
      templateJson.value = ''
      templateStore.clearCurrentDetail()
    }
  }
)
</script>

<template>
  <div class="page-designer">
    <el-page-header v-if="isEditMode" class="page-header" @back="router.push('/template')">
      <template #content>
        <span class="page-title">模板详情 #{{ id }}</span>
      </template>
    </el-page-header>
    <div v-else class="page-header-simple">
      <h2 class="page-title">新建模板</h2>
      <el-button text type="primary" @click="router.push('/template')">返回列表</el-button>
    </div>

    <el-alert
      v-if="isEditMode"
      type="info"
      :closable="false"
      show-icon
      class="hint"
      title="当前为查看模式。后端创建接口为 POST，若需保存修改请先「复制为新建」到新建页编辑后再提交。"
    />

    <el-card shadow="never" class="card">
      <template #header>
        <div class="card-header">
          <span>{{ isEditMode ? '模板 JSON' : '编辑模板 JSON' }}</span>
          <div class="card-actions">
            <el-button v-if="!isEditMode" size="small" @click="loadSample">加载示例</el-button>
            <el-button v-if="isEditMode" size="small" :loading="templateStore.detailLoading" @click="loadDetail">
              刷新
            </el-button>
            <el-button v-if="isEditMode" size="small" type="primary" plain @click="copyAsNew">复制为新建</el-button>
            <el-button
              v-if="!isEditMode"
              type="primary"
              size="small"
              :loading="templateStore.saving"
              @click="saveTemplate"
            >
              保存模板
            </el-button>
          </div>
        </div>
      </template>
      <el-input
        v-model="templateJson"
        type="textarea"
        :rows="28"
        class="json-editor"
        :readonly="isEditMode"
        placeholder="请输入模板 JSON 配置..."
      />
    </el-card>
  </div>
</template>

<style scoped>
.page-designer {
  width: 100%;
}

.page-header {
  margin-bottom: 16px;
}

.page-header-simple {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 16px;
}

.page-title {
  font-size: 18px;
  font-weight: 600;
  color: #303133;
}

.hint {
  margin-bottom: 16px;
}

.card {
  border-radius: 8px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 8px;
}

.card-actions {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
</style>
