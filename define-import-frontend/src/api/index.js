import axios from 'axios'

const api = axios.create({ baseURL: '/api/v1', timeout: 60000 })

export const templateApi = {
  list: (supplierId) => api.get('/import-template/list', { params: { supplierId } }),
  getById: (id) => api.get(`/import-template/${id}`),
  create: (data) => api.post('/import-template', data),
  update: (id, data) => api.put(`/import-template/${id}`, data),
  delete: (id) => api.delete(`/import-template/${id}`)
}

export const importApi = {
  preview: (file, templateId) => {
    const fd = new FormData()
    fd.append('file', file)
    fd.append('templateId', templateId)
    return api.post('/dynamic-import/preview', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  }
}

export const previewApi = {
  upload: (file) => {
    const fd = new FormData()
    fd.append('file', file)
    return api.post('/excel-preview/upload', fd, { headers: { 'Content-Type': 'multipart/form-data' } })
  },
  getSheetData: (fileId, sheetIndex, headerRow = 0, maxRows = 200) =>
    api.get(`/excel-preview/${fileId}/sheet/${sheetIndex}`, { params: { headerRow, maxRows } })
}
