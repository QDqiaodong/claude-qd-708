import axios from 'axios'
import { ElMessage } from 'element-plus'

/**
 * 统一走 nginx 反代到后端 /api：
 *   成功 → 后端包成 { ok:true, message, data }，这里直接把 data 交给页面
 *   失败 → 后端包成 { ok:false, message:"中文业务提示" }，这里统一弹提示并 reject
 */
const http = axios.create({ baseURL: '/api', timeout: 15000 })

http.interceptors.response.use(
  (res) => {
    const body = res.data
    if (body && typeof body === 'object' && Object.prototype.hasOwnProperty.call(body, 'ok')) {
      if (body.ok) return body.data
      ElMessage.error(body.message || '操作失败')
      return Promise.reject(new Error(body.message || '操作失败'))
    }
    return body
  },
  (err) => {
    const body = err.response && err.response.data
    const message = (body && body.message) || err.message || '网络异常，请稍后再试'
    ElMessage.error(message)
    return Promise.reject(new Error(message))
  }
)

/** 赛道实况（大计时器 / 赛道点位 / 排行榜） */
export const trackApi = {
  overview: () => http.get('/track/overview'),
  live: (sessionId) => http.get('/track/live', { params: sessionId ? { sessionId } : {} })
}

/** 模块一：卡丁车 */
export const kartApi = {
  list: () => http.get('/karts'),
  create: (data) => http.post('/karts', data),
  setStatus: (id, status) => http.post(`/karts/${id}/status`, { status }),
  dispatch: (id, sessionId) => http.post(`/karts/${id}/dispatch`, { sessionId }),
  recall: (id) => http.post(`/karts/${id}/recall`)
}

/** 模块二：车手 */
export const driverApi = {
  list: () => http.get('/drivers'),
  leaderboard: () => http.get('/drivers/leaderboard'),
  create: (data) => http.post('/drivers', data),
  enroll: (id, sessionId) => http.post(`/drivers/${id}/enroll`, { sessionId }),
  quit: (id) => http.post(`/drivers/${id}/quit`)
}

/** 模块三：场次 */
export const sessionApi = {
  list: () => http.get('/sessions'),
  create: (data) => http.post('/sessions', data),
  start: (id) => http.post(`/sessions/${id}/start`),
  finish: (id) => http.post(`/sessions/${id}/finish`),
  cancel: (id) => http.post(`/sessions/${id}/cancel`)
}

/** 模块四：圈速记录（新增走实体上的 @SQLInsert 手写 SQL） */
export const lapApi = {
  list: (sessionId) => http.get('/lap-records', { params: sessionId ? { sessionId } : {} }),
  create: (data) => http.post('/lap-records', data),
  setValid: (id, valid) => http.post(`/lap-records/${id}/valid`, { valid }),
  remove: (id) => http.delete(`/lap-records/${id}`)
}

/** 模块五：维修保养 */
export const maintenanceApi = {
  list: () => http.get('/maintenance'),
  summary: () => http.get('/maintenance/summary'),
  create: (data) => http.post('/maintenance', data),
  start: (id, worker) => http.post(`/maintenance/${id}/start`, worker ? { worker } : {}),
  complete: (id) => http.post(`/maintenance/${id}/complete`)
}
