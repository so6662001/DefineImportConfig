<script setup>
import { onMounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { useTemplateStore } from '../store/template'

const router = useRouter()
const templateStore = useTemplateStore()
const supplierId = ref(null)

onMounted(() => {
  templateStore.fetchList(supplierId.value ?? undefined).catch((e) => ElMessage.error(e.message || '加载失败'))
})

function onFilter() {
  templateStore.fetchList(supplierId.value ?? undefined).catch((e) => ElMessage.error(e.message || '加载失败'))
}

function goNew() {
  router.push('/template/designer')
}

function goEdit(id) {
  router.push(`/template/designer/${id}`)
}

async function onDelete(id) {
  try {
    await templateStore.remove(id)
    ElMessage.success('删除成功')
    await templateStore.fetchList(supplierId.value ?? undefined)
  } catch (e) {
    ElMessage.error(e.message || '删除失败')
  }
}
</script>

<template>
  <div class="page-template-list">
    <div class="toolbar">
      <div class="toolbar-left">
        <span class="label">供应商 ID</span>
        <el-input-number
          v-model="supplierId"
          :controls="false"
          placeholder="可选，筛选供应商"
          clearable
          class="supplier-input"
          @change="onFilter"
        />
        <el-button :loading="templateStore.listLoading" @click="onFilter">刷新列表</el-button>
      </div>
      <el-button type="primary" @click="goNew">新建模板</el-button>
    </div>

    <el-table
      v-loading="templateStore.listLoading"
      :data="templateStore.list"
      stripe
      border
      style="width: 100%"
      max-height="560"
    >
      <el-table-column prop="id" label="ID" width="72" />
      <el-table-column prop="templateCode" label="模板编号" width="160" show-overflow-tooltip />
      <el-table-column prop="templateName" label="模板名称" min-width="180" show-overflow-tooltip />
      <el-table-column prop="supplierName" label="供应商" width="140" show-overflow-tooltip />
      <el-table-column prop="status" label="状态" width="88">
        <template #default="{ row }">
          <el-tag :type="row.status === 1 ? 'success' : 'info'" size="small">
            {{ row.status === 1 ? '启用' : '禁用' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button link type="primary" @click="goEdit(row.id)">查看 / 编辑</el-button>
          <el-popconfirm title="确定删除该模板？" @confirm="onDelete(row.id)">
            <template #reference>
              <el-button link type="danger">删除</el-button>
            </template>
          </el-popconfirm>
        </template>
      </el-table-column>
    </el-table>
  </div>
</template>

<style scoped>
.page-template-list {
  width: 100%;
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  flex-wrap: wrap;
  gap: 12px;
  margin-bottom: 20px;
}

.toolbar-left {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
}

.label {
  font-size: 14px;
  color: #606266;
}

.supplier-input {
  width: 200px;
}
</style>
