<script setup>
import { computed } from 'vue'
import { useRoute } from 'vue-router'

const route = useRoute()

const activeMenu = computed(() => {
  const p = route.path
  if (p.startsWith('/template')) return '/template'
  if (p.startsWith('/import')) return '/import'
  if (p.startsWith('/preview')) return '/preview'
  return '/template'
})
</script>

<template>
  <div class="app-root">
    <header class="app-header">
      <div class="app-header-inner">
        <h1 class="app-title">钢贸动态Excel导入系统</h1>
        <p class="app-subtitle">基于动态映射规则的 Excel 导入与预览</p>
        <el-menu
          class="app-menu"
          mode="horizontal"
          :ellipsis="false"
          :router="true"
          :default-active="activeMenu"
          background-color="transparent"
          text-color="rgba(255,255,255,0.88)"
          active-text-color="#ffffff"
        >
          <el-menu-item index="/template">模板管理</el-menu-item>
          <el-menu-item index="/import">导入测试</el-menu-item>
          <el-menu-item index="/preview">Excel预览</el-menu-item>
        </el-menu>
      </div>
    </header>
    <main class="app-main">
      <div class="app-content">
        <router-view />
      </div>
    </main>
  </div>
</template>

<style scoped>
.app-root {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  background: #f0f2f5;
}

.app-header {
  background: linear-gradient(135deg, #1a237e, #283593);
  color: #fff;
  padding: 0 24px;
  box-shadow: 0 2px 12px rgba(26, 35, 126, 0.35);
}

.app-header-inner {
  max-width: 1400px;
  margin: 0 auto;
  padding: 16px 0 0;
}

.app-title {
  font-size: 20px;
  font-weight: 600;
  margin: 0;
  letter-spacing: 0.02em;
}

.app-subtitle {
  font-size: 13px;
  opacity: 0.85;
  margin: 6px 0 0;
}

.app-menu {
  margin-top: 12px;
  border-bottom: none !important;
  --el-menu-bg-color: transparent;
  --el-menu-hover-bg-color: rgba(255, 255, 255, 0.12);
  --el-menu-active-color: #ffffff;
}

.app-menu :deep(.el-menu-item) {
  font-size: 15px;
  border-bottom: 2px solid transparent !important;
}

.app-menu :deep(.el-menu-item.is-active) {
  border-bottom-color: #fff !important;
  background: transparent !important;
}

.app-main {
  flex: 1;
  padding: 20px;
}

.app-content {
  max-width: 1400px;
  margin: 0 auto;
  background: #fff;
  border-radius: 8px;
  padding: 24px;
  min-height: calc(100vh - 200px);
  box-shadow: 0 1px 3px rgba(0, 0, 0, 0.06);
}
</style>
