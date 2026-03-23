import { defineStore } from 'pinia'
import { templateApi } from '../api'

function unwrap(res) {
  const body = res?.data
  if (body?.code === 200) return body.data
  throw new Error(body?.message || '请求失败')
}

export const useTemplateStore = defineStore('template', {
  state: () => ({
    list: [],
    currentDetail: null,
    listLoading: false,
    detailLoading: false,
    saving: false
  }),
  actions: {
    async fetchList(supplierId) {
      this.listLoading = true
      try {
        const res = await templateApi.list(supplierId)
        this.list = unwrap(res) || []
      } finally {
        this.listLoading = false
      }
    },
    async fetchById(id) {
      this.detailLoading = true
      try {
        const res = await templateApi.getById(id)
        this.currentDetail = unwrap(res)
        return this.currentDetail
      } finally {
        this.detailLoading = false
      }
    },
    clearCurrentDetail() {
      this.currentDetail = null
    },
    async createTemplate(payload) {
      this.saving = true
      try {
        const res = await templateApi.create(payload)
        return unwrap(res)
      } finally {
        this.saving = false
      }
    },
    async remove(id) {
      const res = await templateApi.delete(id)
      unwrap(res)
    }
  }
})
