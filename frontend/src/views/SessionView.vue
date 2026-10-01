<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">场次列表</span>
        <span class="panel-extra">待开赛 → 进行中 → 已结束 / 已取消</span>
        <el-button type="danger" size="small" style="margin-left: auto" @click="openCreate">＋ 新建场次</el-button>
      </div>
      <el-table :data="rows" size="small" stripe>
        <el-table-column prop="sessionNo" label="场次号" width="130" />
        <el-table-column label="赛道配置" width="100">
          <template #default="{ row }">{{ row.trackConfigName }}</template>
        </el-table-column>
        <el-table-column label="分组" width="70">
          <template #default="{ row }">{{ row.groupCode }} 组</template>
        </el-table-column>
        <el-table-column prop="startTime" label="开始时间" width="170" />
        <el-table-column prop="endTime" label="结束时间" width="170" />
        <el-table-column label="车辆" width="90">
          <template #default="{ row }">{{ row.signedCount }}/{{ row.maxKarts }}</template>
        </el-table-column>
        <el-table-column label="时长" width="90">
          <template #default="{ row }">{{ fmtDuration(row.elapsedSec) }}</template>
        </el-table-column>
        <el-table-column label="圈数" width="70" prop="lapCount" />
        <el-table-column label="最快圈" width="110" prop="fastestText" />
        <el-table-column label="状态" width="110">
          <template #default="{ row }">
            <span class="tag" :class="statusClass(row.status)">{{ row.statusName }}</span>
            <el-tooltip v-if="row.redFlagActive" content="本场红旗处置中，前往红旗处置台" placement="top">
              <el-button link type="danger" style="margin-left: 2px" @click="goConsole">🚩 处置</el-button>
            </el-tooltip>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="260">
          <template #default="{ row }">
            <el-button v-if="row.status === 'PENDING'" link type="success" @click="act(row, 'start')">开赛</el-button>
            <el-button v-if="row.status === 'RUNNING' && !row.redFlagActive" link type="warning" @click="act(row, 'finish')">结束</el-button>
            <el-button v-if="row.status === 'PENDING'" link type="danger" @click="act(row, 'cancel')">取消</el-button>
            <el-button link @click="focus(row)">设为本场</el-button>
            <el-button link type="danger" @click="viewRedFlags(row)">红旗记录</el-button>
          </template>
        </el-table-column>
      </el-table>
    </div>

    <!-- 场次历史红旗事件回看 -->
    <el-dialog v-model="historyDialog" :title="`红旗事件记录 · ${historySessionNo}`" width="920">
      <el-table :data="historyRows" size="small" stripe max-height="520">
        <el-table-column prop="eventNo" label="事件号" width="170" />
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column label="发生时刻" width="165">
          <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="处置" width="100">
          <template #default="{ row }">
            <span class="tag" :class="row.status === 'RESUMED' ? 'ok' : 'danger'">{{ row.statusName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="持续" width="70">
          <template #default="{ row }">{{ fmtDuration(row.durationSec) }}</template>
        </el-table-column>
        <el-table-column label="继续/退出/送修" width="120">
          <template #default="{ row }">{{ row.checkContinue }}/{{ row.checkRetire }}/{{ row.checkRepair }}</template>
        </el-table-column>
        <el-table-column label="确认/作废" width="90">
          <template #default="{ row }">{{ row.lapConfirmed }}/{{ row.lapVoided }}</template>
        </el-table-column>
      </el-table>
      <div v-if="!historyRows.length" class="empty">该场次没有红旗事件</div>
    </el-dialog>

    <el-dialog v-model="dialog" title="新建场次" width="420">
      <el-form label-width="90px">
        <el-form-item label="场次号">
          <el-input v-model="form.sessionNo" placeholder="例如 S2026092403" />
        </el-form-item>
        <el-form-item label="赛道配置">
          <el-select v-model="form.trackConfig" style="width: 100%">
            <el-option label="半场（最多 8 台）" value="HALF" />
            <el-option label="全场（最多 12 台）" value="FULL" />
            <el-option label="夜间（最多 10 台）" value="NIGHT" />
          </el-select>
        </el-form-item>
        <el-form-item label="分组">
          <el-select v-model="form.groupCode" style="width: 100%">
            <el-option label="A 组" value="A" />
            <el-option label="B 组" value="B" />
            <el-option label="C 组" value="C" />
          </el-select>
        </el-form-item>
        <el-form-item label="最大车辆">
          <el-input v-model.number="form.maxKarts" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialog = false">取消</el-button>
        <el-button type="danger" @click="submit">创建</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { inject, onMounted, ref, watch } from 'vue'
import { useRouter } from 'vue-router'
import { redFlagApi, sessionApi } from '../api'

const nav = useRouter()
const track = inject('track')
const rows = ref([])
const dialog = ref(false)
const form = ref({ sessionNo: '', trackConfig: 'FULL', groupCode: 'A', maxKarts: 12 })
const historyDialog = ref(false)
const historyRows = ref([])
const historySessionNo = ref('')

async function load() {
  rows.value = await sessionApi.list()
}

function goConsole() {
  nav.push('/red-flag')
}

async function viewRedFlags(row) {
  track.sessionId.value = row.id
  historySessionNo.value = row.sessionNo
  historyRows.value = await redFlagApi.history(row.id)
  historyDialog.value = true
}

function statusClass(status) {
  if (status === 'RUNNING') return 'running'
  if (status === 'FINISHED') return 'ok'
  if (status === 'CANCELED') return 'mute'
  return 'warn'
}

function fmtDuration(sec) {
  const s = Math.max(0, sec || 0)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}

function fmtTime(t) {
  return t ? String(t).replace('T', ' ').substring(0, 19) : '—'
}

function openCreate() {
  const stamp = new Date()
  const date = `${stamp.getFullYear()}${String(stamp.getMonth() + 1).padStart(2, '0')}${String(stamp.getDate()).padStart(2, '0')}`
  form.value = { sessionNo: `S${date}03`, trackConfig: 'FULL', groupCode: 'A', maxKarts: 12 }
  dialog.value = true
}

async function submit() {
  await sessionApi.create(form.value)
  dialog.value = false
  await load()
  await track.reloadSessions()
}

async function act(row, type) {
  if (type === 'start') await sessionApi.start(row.id)
  if (type === 'finish') await sessionApi.finish(row.id)
  if (type === 'cancel') await sessionApi.cancel(row.id)
  await load()
  await track.reloadSessions()
  await track.refreshOverview()
}

function focus(row) {
  track.sessionId.value = row.id
}

watch(() => track.sessionId.value, () => load())
onMounted(load)
</script>
