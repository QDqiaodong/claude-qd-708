<template>
  <div class="shell">
    <!-- 顶栏：品牌 + 本场切换 + 计数器 -->
    <header class="topbar">
      <div class="brand">
        <span class="brand-mark">🏁</span>
        <div>
          极速卡丁 · 赛道运营管理台
          <div class="brand-sub">{{ todayText }} · 在赛 {{ overview.kartOnTrack || 0 }} 台 · 待修 {{ overview.maintOpen || 0 }} 单</div>
        </div>
      </div>

      <div class="tail-picker" style="margin-left: 18px">
        <el-select v-model="sessionId" placeholder="选择场次" style="width: 230px" @change="onPickSession">
          <el-option v-for="s in sessions" :key="s.id" :label="s.sessionNo + ' · ' + s.trackConfigName + s.groupCode + '组'" :value="s.id">
            <span>{{ s.sessionNo }} · {{ s.trackConfigName }}{{ s.groupCode }}组</span>
            <span style="float: right; font-size: 12px; opacity: 0.7">{{ s.statusName }}</span>
          </el-option>
        </el-select>
      </div>

      <div class="top-stats">
        <div class="stat-chip"><b>{{ overview.kartAvailable || 0 }}/{{ overview.kartTotal || 0 }}</b><span>可用车辆</span></div>
        <div class="stat-chip"><b>{{ overview.driverTotal || 0 }}</b><span>注册车手</span></div>
        <div class="stat-chip"><b>{{ overview.sessionRunning || 0 }}</b><span>进行中场次</span></div>
        <div class="stat-chip"><b>{{ overview.lapTotal || 0 }}</b><span>圈速记录</span></div>
        <div class="stat-chip" :class="{ 'is-alert': (overview.maintOpen || 0) > 0 }">
          <b>{{ overview.maintOpen || 0 }}</b><span>未完工维修</span>
        </div>
      </div>
    </header>

    <!-- Tab 导航 -->
    <nav class="navbar">
      <button
        v-for="item in navItems"
        :key="item.key"
        class="nav-btn"
        :class="{ 'is-on': activeKey === item.key }"
        type="button"
        :title="item.hint"
        @click="go(item.path)"
      >
        <span class="nav-ico">{{ item.icon }}</span>{{ item.label }}
      </button>
    </nav>

    <main class="stage">
      <router-view />
    </main>
  </div>
</template>

<script setup>
import { computed, getCurrentInstance, onMounted, provide, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import router, { navItems } from './router'
import { sessionApi, trackApi } from './api'

/** main.js 只装了 Element Plus，router 在这里装到根实例上（install 幂等） */
getCurrentInstance().appContext.app.use(router)

const route = useRoute()
const nav = useRouter()

const sessions = ref([])
const sessionId = ref(null)
const overview = ref({})

/** 全站共享的「本场」上下文 */
const track = {
  sessionId,
  sessions,
  overview,
  refreshOverview: loadOverview,
  reloadSessions: loadSessions
}
provide('track', track)

const todayText = computed(() => {
  const d = new Date()
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, '0')}-${String(d.getDate()).padStart(2, '0')}`
})

const activeKey = computed(() => {
  const hit = navItems.find((item) => item.path !== '/' && route.path.startsWith(item.path))
  if (hit) return hit.key
  return route.path === '/' ? 'home' : 'home'
})

function go(path) {
  nav.push(path)
}

function onPickSession(id) {
  localStorage.setItem('kart-session-id', String(id == null ? '' : id))
}

async function loadSessions() {
  sessions.value = await sessionApi.list()
  if (!sessionId.value) {
    const saved = Number(localStorage.getItem('kart-session-id'))
    const hit = sessions.value.find((s) => s.id === saved)
    const running = sessions.value.find((s) => s.status === 'RUNNING')
    sessionId.value = hit ? hit.id : (running ? running.id : (sessions.value[0] ? sessions.value[0].id : null))
  }
}

async function loadOverview() {
  overview.value = await trackApi.overview()
}

onMounted(async () => {
  await loadSessions()
  await loadOverview()
})
</script>
