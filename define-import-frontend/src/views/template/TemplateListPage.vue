<script setup>
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { templateApi } from '@/api/index.js'
import { ElMessage } from 'element-plus'
import { Plus, Refresh, View, Edit, Delete } from '@element-plus/icons-vue'

const router = useRouter()
const loading = ref(false)
const list = ref([])
const drawerVisible = ref(false)
const detail = ref(null)

const statusMap = {
  ACTIVE: { text: '启用', type: 'success' },
  INACTIVE: { text: '停用', type: 'info' },
  DRAFT: { text: '草稿', type: 'warning' }
}

const getStatusTag = (status) => {
  return statusMap[status] || { text: status || '未知', type: 'info' }
}

const loadList = async () => {
  loading.value = true
  try {
    const { data } = await templateApi.list()
    list.value = Array.isArray(data) ? data : (data.data || [])
  } catch (e) {
    ElMessage.error('加载模板列表失败')
  } finally {
    loading.value = false
  }
}

const viewDetail = async (row) => {
  try {
    const { data } = await templateApi.getById(row.id)
    detail.value = data.data || data
    drawerVisible.value = true
  } catch (e) {
    ElMessage.error('加载模板详情失败')
  }
}

const handleEdit = (row) => {
  router.push(`/template/designer/${row.id}`)
}

const handleDelete = async (id) => {
  try {
    await templateApi.delete(id)
    ElMessage.success('删除成功')
    loadList()
  } catch (e) {
    ElMessage.error('删除失败')
  }
}

onMounted(() => {
  loadList()
})
</script>

<template>
  <div class="page-container">
    <el-card shadow="never">
      <template #header>
        <div class="card-header">
          <span class="title">导入模板管理</span>
          <div class="header-actions">
            <el-button type="primary" @click="$router.push('/template/designer')">
              <el-icon><Plus /></el-icon> 新建模板
            </el-button>
            <el-button @click="loadList" :loading="loading">
              <el-icon><Refresh /></el-icon> 刷新
            </el-button>
          </div>
        </div>
      </template>

      <el-table :data="list" v-loading="loading" stripe border empty-text="暂无模板数据">
        <el-table-column prop="id" label="ID" width="70" align="center" />
        <el-table-column prop="templateCode" label="模板编号" min-width="130" show-overflow-tooltip />
        <el-table-column prop="templateName" label="模板名称" min-width="160" show-overflow-tooltip />
        <el-table-column label="供应商" min-width="140" show-overflow-tooltip>
          <template #default="{ row }">
            {{ row.supplierName || '-' }}
            <span v-if="row.supplierId" class="supplier-id">(ID: {{ row.supplierId }})</span>
          </template>
        </el-table-column>
        <el-table-column prop="status" label="状态" width="90" align="center">
          <template #default="{ row }">
            <el-tag :type="getStatusTag(row.status).type" size="small">
              {{ getStatusTag(row.status).text }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="createdAt" label="创建时间" width="170" show-overflow-tooltip />
        <el-table-column label="操作" width="200" align="center" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" size="small" @click="viewDetail(row)">
              <el-icon><View /></el-icon> 查看
            </el-button>
            <el-button link type="primary" size="small" @click="handleEdit(row)">
              <el-icon><Edit /></el-icon> 编辑
            </el-button>
            <el-popconfirm
              title="确定删除该模板？删除后不可恢复。"
              confirm-button-text="确定"
              cancel-button-text="取消"
              @confirm="handleDelete(row.id)"
            >
              <template #reference>
                <el-button link type="danger" size="small">
                  <el-icon><Delete /></el-icon> 删除
                </el-button>
              </template>
            </el-popconfirm>
          </template>
        </el-table-column>
      </el-table>
    </el-card>

    <el-drawer v-model="drawerVisible" title="模板详情" size="50%">
      <pre class="json-view">{{ detail ? JSON.stringify(detail, null, 2) : '' }}</pre>
    </el-drawer>
  </div>
</template>

<style scoped>
.page-container {
  padding: 4px;
}

.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}

.card-header .title {
  font-size: 16px;
  font-weight: 600;
  color: #303133;
}

.header-actions {
  display: flex;
  gap: 8px;
}

.supplier-id {
  color: #909399;
  font-size: 12px;
}

.json-view {
  background: #f5f7fa;
  border: 1px solid #e4e7ed;
  border-radius: 6px;
  padding: 16px;
  font-family: 'Consolas', 'Monaco', 'Menlo', monospace;
  font-size: 13px;
  line-height: 1.5;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: calc(100vh - 160px);
  overflow-y: auto;
  color: #303133;
}
</style>
