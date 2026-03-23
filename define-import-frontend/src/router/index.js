import { createRouter, createWebHistory } from 'vue-router'
import TemplateListPage from '@/views/template/TemplateListPage.vue'
import TemplateDesignerPage from '@/views/template/TemplateDesignerPage.vue'
import DynamicImportPage from '@/views/import/DynamicImportPage.vue'
import ExcelPreviewPage from '@/views/ExcelPreviewPage.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/', redirect: '/template' },
    { path: '/template', name: 'TemplateList', component: TemplateListPage },
    { path: '/template/designer', name: 'TemplateDesignerNew', component: TemplateDesignerPage },
    { path: '/template/designer/:id', name: 'TemplateDesignerEdit', component: TemplateDesignerPage, props: true },
    { path: '/import', name: 'DynamicImport', component: DynamicImportPage },
    { path: '/preview', name: 'ExcelPreview', component: ExcelPreviewPage }
  ]
})

export default router
