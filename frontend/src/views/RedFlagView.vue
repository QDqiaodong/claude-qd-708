<template>
  <div>
    <!-- 当前场次上下文 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">红旗事件处置台</span>
        <span class="panel-extra">
          当前身份：
          <el-tag :type="isSupervisor ? 'danger' : 'warning'" size="small">{{ isSupervisor ? '主管' : '值班员' }}</el-tag>
          <span style="margin-left: 8px">
            场次：<b>{{ session ? session.sessionNo : '—' }}</b>
            <el-tag v-if="session" size="small" :type="session.redFlagActive ? 'danger' : 'success'" style="margin-left: 6px">
              {{ session.statusName }}
            </el-tag>
          </span>
        </span>
      </div>
      <div v-if="!session" class="empty">请先在顶栏选择一个场次</div>
      <div v-else-if="!event && session.status !== 'RUNNING'" class="empty">
        该场次为「{{ session.statusName }}」，只有进行中的场次可以发起红旗；可在下方回看历史红旗事件。
      </div>
      <div v-else-if="!event" class="rf-idle">
        <div class="empty">本场比赛正常进行中。出现事故时由<b>值班员</b>发起红旗，系统将立即暂停圈速入榜并生成在赛车辆核查清单。</div>
        <div style="text-align: center">
          <el-button type="danger" size="large" :disabled="!canRaise" @click="openRaise">🚩 发起红旗</el-button>
          <div v-if="!canRaise" style="font-size: 12px; color: var(--kt-text-sub); margin-top: 6px">
            只有值班员身份可以发起红旗，请在顶栏切换身份
          </div>
        </div>
      </div>
    </div>

    <!-- 生效红旗 / 最近一次已处置红旗 -->
    <template v-if="event">
      <div class="rf-banner" :class="{ 'is-closed': event.status !== 'ACTIVE' }">
        <div class="rf-banner-head">
          <span class="rf-banner-title">🚩 {{ event.statusName }} · {{ event.eventNo }}</span>
          <el-tag size="small" :type="event.status === 'ACTIVE' ? 'danger' : 'info'" effect="dark">
            {{ event.status === 'ACTIVE' ? '圈速入榜已暂停' : '本场' + event.resolutionName }}
          </el-tag>
          <span class="rf-duration">{{ fmtDuration(event.durationSec) }}</span>
        </div>
        <div class="rf-reason">红旗原因：{{ event.reason }}</div>
        <div class="rf-meta">
          <span>发生时刻：{{ fmtTime(event.occurredAt) }}</span>
          <span>发起：{{ event.raisedBy }}</span>
          <span>所属场次：{{ event.sessionNo }}</span>
          <span v-if="event.status !== 'ACTIVE'">处置：{{ event.resolutionName }} · {{ event.resolvedBy }} · {{ fmtTime(event.resolvedAt) }}</span>
        </div>
        <div v-if="event.status !== 'ACTIVE' && event.resolutionNote" class="rf-reason">处置备注：{{ event.resolutionNote }}</div>
      </div>

      <!-- 进度卡 -->
      <div class="rf-progress-grid">
        <div class="sum-card accent">
          <div class="label">车辆核查进度</div>
          <div class="value">{{ event.checkTotal - event.checkPending }}/{{ event.checkTotal }}</div>
        </div>
        <div class="sum-card"><div class="label">可继续</div><div class="value" style="color: var(--kt-ok)">{{ event.checkContinue }}</div></div>
        <div class="sum-card"><div class="label">退出比赛</div><div class="value" style="color: var(--kt-warn)">{{ event.checkRetire }}</div></div>
        <div class="sum-card"><div class="label">需要维修</div><div class="value" style="color: var(--kt-danger)">{{ event.checkRepair }}</div></div>
        <div class="sum-card accent">
          <div class="label">待裁定圈速</div>
          <div class="value">{{ event.lapPending }}</div>
        </div>
        <div class="sum-card"><div class="label">已确认 / 已作废</div>
          <div class="value" style="font-size: 17px; padding-top: 6px">
            <span style="color: var(--kt-ok)">{{ event.lapConfirmed }}</span>
            /
            <span style="color: var(--kt-text-sub)">{{ event.lapVoided }}</span>
          </div>
        </div>
      </div>

      <!-- 车辆核查清单 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">受影响车辆核查清单</span>
          <span class="panel-extra">
            发起红旗时以当时仍在赛车辆生成 · 各车结论互相独立 ·
            <b style="color: var(--kt-ok)">可继续</b> /
            <b style="color: var(--kt-warn)">退出</b> /
            <b style="color: var(--kt-danger)">送修</b>
          </span>
        </div>
        <el-table :data="event.checks" size="small" stripe>
          <el-table-column label="车号" width="90">
            <template #default="{ row }"><b>{{ row.kartNo }}</b> <span style="color: #90a4ae; font-size: 12px">{{ row.modelName }}</span></template>
          </el-table-column>
          <el-table-column prop="driverName" label="车手" width="110" />
          <el-table-column label="核查结论" width="130">
            <template #default="{ row }">
              <span class="tag" :class="checkTagClass(row.result)">{{ row.resultName }}</span>
            </template>
          </el-table-column>
          <el-table-column prop="note" label="备注 / 故障" min-width="180" show-overflow-tooltip />
          <el-table-column label="维修单" width="160">
            <template #default="{ row }">
              <el-tag v-if="row.maintenanceOrderNo" size="small" type="danger">{{ row.maintenanceOrderNo }} · 待修</el-tag>
              <span v-else style="color: #b0bec5">—</span>
            </template>
          </el-table-column>
          <el-table-column label="核查人/时间" width="190">
            <template #default="{ row }">
              <div v-if="row.checkedAt" style="font-size: 12px">
                {{ row.checkedBy }}<br />{{ fmtTime(row.checkedAt) }}
              </div>
              <span v-else style="color: #b0bec5">待值班员核查</span>
            </template>
          </el-table-column>
          <el-table-column label="操作" width="300">
            <template #default="{ row }">
              <template v-if="event.status === 'ACTIVE' && row.result === 'PENDING'">
                <el-button link type="success" :disabled="!canDuty" @click="openCheck(row, 'CONTINUE')">可继续</el-button>
                <el-button link type="warning" :disabled="!canDuty" @click="openCheck(row, 'RETIRE')">退出比赛</el-button>
                <el-button link type="danger" :disabled="!canDuty" @click="openCheck(row, 'REPAIR')">需要维修</el-button>
              </template>
              <span v-else-if="event.status !== 'ACTIVE'" style="color: #b0bec5; font-size: 12px">事件已关闭</span>
              <span v-else style="color: var(--kt-ok); font-size: 12px">已核查锁定，不可改写</span>
            </template>
          </el-table-column>
        </el-table>
      </div>

      <!-- 红旗期间圈速 -->
      <div class="panel">
        <div class="panel-head">
          <span class="panel-title">红旗期间计时上报（{{ event.laps.length }} 条）</span>
          <span class="panel-extra">数据不丢弃、不污染排行榜 · 由主管逐条确认有效或作废 · 未裁定不计最快圈/个人最佳</span>
        </div>
        <el-table :data="event.laps" size="small" stripe :row-class-name="lapRowClass">
          <el-table-column prop="lapIndex" label="圈序" width="70" />
          <el-table-column prop="driverName" label="车手" width="100" />
          <el-table-column prop="kartNo" label="车号" width="80" />
          <el-table-column label="圈速" width="120">
            <template #default="{ row }"><b>{{ row.lapTimeText }}</b></template>
          </el-table-column>
          <el-table-column prop="createdAt" label="设备上报时间" min-width="170" />
          <el-table-column label="裁定状态" width="120">
            <template #default="{ row }">
              <span class="tag" :class="adjTagClass(row.adjudication)">{{ row.adjudicationName }}</span>
              <span v-if="row.fastest" class="tag danger" style="margin-left: 4px">🏁 最快圈</span>
            </template>
          </el-table-column>
          <el-table-column label="主管操作" width="200">
            <template #default="{ row }">
              <template v-if="event.status === 'ACTIVE' && row.adjudication === 'PENDING'">
                <el-button link type="success" :disabled="!isSupervisor" @click="adjudicate(row, 'CONFIRMED')">确认有效</el-button>
                <el-button link type="danger" :disabled="!isSupervisor" @click="adjudicate(row, 'VOIDED')">作废</el-button>
              </template>
              <span v-else style="color: #b0bec5; font-size: 12px">
                {{ row.adjudication === 'PENDING' ? '事件已关闭（已统一作废）' : '已裁定，刷新不会重复入榜' }}
              </span>
            </template>
          </el-table-column>
        </el-table>
        <div v-if="!event.laps.length" class="empty">红旗期间暂无计时设备上报圈速</div>
      </div>

      <!-- 主管收口 -->
      <div v-if="event.status === 'ACTIVE'" class="panel">
        <div class="panel-head"><span class="panel-title">最终处置（仅主管）</span></div>
        <div class="rf-guard">
          <div>
            <el-button type="success" size="large" :disabled="!event.canResume || !isSupervisor" @click="openResolve('resume')">
              恢复比赛
            </el-button>
            <el-button type="danger" size="large" :disabled="!event.canTerminate || !isSupervisor" @click="openResolve('terminate')">
              强制终止
            </el-button>
          </div>
          <ul class="rf-rules">
            <li :class="{ ok: event.allChecked }">所有车辆已核查（{{ event.checkTotal - event.checkPending }}/{{ event.checkTotal }}）</li>
            <li :class="{ ok: event.allAdjudicated }">红旗期间圈速已全部裁定（待裁定 {{ event.lapPending }} 条）</li>
            <li :class="{ ok: true }">场次仍处于本次事件控制下（{{ event.sessionNo }} · 红旗处理中）</li>
            <li class="muted">恢复后可继续记圈；强制终止将一次性结束场次、释放车手与可用车辆，退出/送修车辆保留状态</li>
          </ul>
        </div>
        <div v-if="!isSupervisor" style="font-size: 12px; color: var(--kt-warn)">当前为值班员身份，恢复/强制终止只能由主管执行。</div>
      </div>

      <!-- 已处置结果回显 -->
      <div v-else class="panel rf-closed-panel">
        <el-icon />
        <span>本次红旗已于 {{ fmtTime(event.resolvedAt) }} 由 {{ event.resolvedBy }} 执行「{{ event.resolutionName }}」。
          核查 {{ event.checkContinue }} 台继续 / {{ event.checkRetire }} 台退出 / {{ event.checkRepair }} 台送修，
          圈速确认 {{ event.lapConfirmed }} 条 / 作废 {{ event.lapVoided }} 条。</span>
      </div>
    </template>

    <!-- 历史红旗事件 -->
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">本场历史红旗事件</span>
        <span class="panel-extra">场次详情回看</span>
      </div>
      <el-table :data="history" size="small" stripe>
        <el-table-column prop="eventNo" label="事件号" width="180" />
        <el-table-column prop="reason" label="原因" min-width="180" show-overflow-tooltip />
        <el-table-column prop="raisedBy" label="发起" width="90" />
        <el-table-column label="发生时刻" width="170">
          <template #default="{ row }">{{ fmtTime(row.occurredAt) }}</template>
        </el-table-column>
        <el-table-column label="处置" width="110">
          <template #default="{ row }">
            <span class="tag" :class="row.status === 'RESUMED' ? 'ok' : 'danger'">{{ row.statusName }}</span>
          </template>
        </el-table-column>
        <el-table-column label="持续" width="80">
          <template #default="{ row }">{{ fmtDuration(row.durationSec) }}</template>
        </el-table-column>
        <el-table-column label="核查 继续/退出/送修" width="150">
          <template #default="{ row }">{{ row.checkContinue }} / {{ row.checkRetire }} / {{ row.checkRepair }}</template>
        </el-table-column>
        <el-table-column label="圈速 确认/作废" width="120">
          <template #default="{ row }">{{ row.lapConfirmed }} / {{ row.lapVoided }}</template>
        </el-table-column>
        <el-table-column label="处置人/时间" width="190">
          <template #default="{ row }">
            <div v-if="row.resolvedAt" style="font-size: 12px">{{ row.resolvedBy }}<br />{{ fmtTime(row.resolvedAt) }}</div>
            <span v-else>—</span>
          </template>
        </el-table-column>
        <el-table-column label="操作" width="90">
          <template #default="{ row }">
            <el-button link @click="viewEvent(row.id)">查看</el-button>
          </template>
        </el-table-column>
      </el-table>
      <div v-if="!history.length" class="empty">本场没有红旗事件记录</div>
    </div>

    <!-- 发起红旗 -->
    <el-dialog v-model="raiseDialog" title="🚩 发起红旗" width="460">
      <el-alert type="warning" :closable="false" style="margin-bottom: 12px"
        title="发起后本场立即进入红旗处理中：圈速暂停入榜，并以当前仍在赛车辆生成待核查清单。" />
      <el-form label-width="80px">
        <el-form-item label="红旗原因" required>
          <el-input v-model="raiseForm.reason" type="textarea" :rows="3"
            placeholder="例如：3 号弯两车碰撞、护板碎片散落赛道，需清场并核查车辆" />
        </el-form-item>
        <el-form-item label="发生时刻">
          <el-input v-model="raiseForm.occurredAt" placeholder="留空取当前时刻，格式 2026-09-30T14:05:00" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="raiseDialog = false">取消</el-button>
        <el-button type="danger" @click="submitRaise">确认发起红旗</el-button>
      </template>
    </el-dialog>

    <!-- 车辆核查 -->
    <el-dialog v-model="checkDialog" :title="`车辆核查 · ${checkForm.kartNo}（${checkForm.resultName}）`" width="460">
      <el-form label-width="80px">
        <el-form-item label="车手">
          <span>{{ checkForm.driverName }}</span>
        </el-form-item>
        <el-form-item label="结论">
          <el-tag :type="checkForm.result === 'REPAIR' ? 'danger' : checkForm.result === 'RETIRE' ? 'warning' : 'success'">
            {{ checkForm.resultName }}
          </el-tag>
        </el-form-item>
        <el-form-item :label="checkForm.result === 'REPAIR' ? '故障描述' : '备注'" :required="checkForm.result === 'REPAIR'">
          <el-input v-model="checkForm.note" type="textarea" :rows="3"
            :placeholder="checkForm.result === 'REPAIR' ? '送修必填：将生成待修维修单并把车辆置为维修状态' : '可选'" />
        </el-form-item>
      </el-form>
      <el-alert v-if="checkForm.result === 'RETIRE'" type="warning" :closable="false"
        title="该车将退出比赛并释放其车手；不影响其它车辆已给出的核查结论。" />
      <el-alert v-if="checkForm.result === 'REPAIR'" type="error" :closable="false" style="margin-top: 8px"
        title="车辆立即进入维修状态并生成可追踪的待修维修单，车手同步释放。" />
      <template #footer>
        <el-button @click="checkDialog = false">取消</el-button>
        <el-button :type="checkForm.result === 'CONTINUE' ? 'success' : 'danger'" @click="submitCheck">提交核查</el-button>
      </template>
    </el-dialog>

    <!-- 恢复/终止 -->
    <el-dialog v-model="resolveDialog" :title="resolveForm.kind === 'resume' ? '恢复比赛' : '强制终止'" width="460">
      <el-alert v-if="resolveForm.kind === 'resume'" type="success" :closable="false"
        title="确认后本场恢复进行中，可继续记圈；旧待裁定记录均已定性，不会再次入榜。" />
      <el-alert v-else type="error" :closable="false"
        title="将一次性结束场次、释放全部车手与可用车辆；退出/送修车辆保留状态，残留待裁定圈速统一作废。任一步失败整体回滚。" />
      <el-form label-width="80px" style="margin-top: 12px">
        <el-form-item label="处置备注">
          <el-input v-model="resolveForm.note" type="textarea" :rows="2" placeholder="可选" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="resolveDialog = false">取消</el-button>
        <el-button :type="resolveForm.kind === 'resume' ? 'success' : 'danger'" @click="submitResolve">确认</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, inject, onBeforeUnmount, onMounted, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { redFlagApi } from '../api'

const track = inject('track')

const session = computed(() => (track.sessions.value || []).find((s) => s.id === track.sessionId.value) || null)
const event = ref(null)
const history = ref(null)
const viewingClosed = ref(null)

const isSupervisor = computed(() => track.role.value === 'SUPERVISOR')
const canDuty = computed(() => track.role.value === 'DUTY')
const canRaise = computed(() => canDuty.value && session.value && session.value.status === 'RUNNING')

const raiseDialog = ref(false)
const raiseForm = ref({ reason: '', occurredAt: '' })
const checkDialog = ref(false)
const checkForm = ref({})
const resolveDialog = ref(false)
const resolveForm = ref({ kind: 'resume', note: '' })

let pollId = null

function fmtDuration(sec) {
  const s = Math.max(0, sec || 0)
  return `${String(Math.floor(s / 60)).padStart(2, '0')}:${String(s % 60).padStart(2, '0')}`
}
function fmtTime(t) {
  return t ? String(t).replace('T', ' ').substring(0, 19) : '—'
}
function checkTagClass(result) {
  if (result === 'CONTINUE') return 'ok'
  if (result === 'RETIRE') return 'warn'
  if (result === 'REPAIR') return 'danger'
  return 'mute'
}
function adjTagClass(adj) {
  if (adj === 'CONFIRMED') return 'ok'
  if (adj === 'VOIDED') return 'mute'
  return 'warn'
}
function lapRowClass({ row }) {
  if (row.adjudication === 'PENDING') return 'row-pending'
  if (row.adjudication === 'VOIDED') return 'row-voided'
  return ''
}

async function loadEvent() {
  if (!track.sessionId.value) {
    event.value = null
    history.value = []
    return
  }
  const active = await redFlagApi.active(track.sessionId.value)
  if (active) {
    event.value = active
  } else if (viewingClosed.value) {
    event.value = await redFlagApi.detail(viewingClosed.value)
  } else {
    event.value = null
  }
  history.value = await redFlagApi.history(track.sessionId.value)
}

function openRaise() {
  raiseForm.value = { reason: '', occurredAt: '' }
  raiseDialog.value = true
}

async function submitRaise() {
  if (!raiseForm.value.reason.trim()) {
    ElMessage.warning('请填写红旗原因')
    return
  }
  const payload = { reason: raiseForm.value.reason.trim() }
  if (raiseForm.value.occurredAt.trim()) payload.occurredAt = raiseForm.value.occurredAt.trim()
  await redFlagApi.raise(track.sessionId.value, payload)
  raiseDialog.value = false
  ElMessage.success('红旗已发起（并发重复发起会返回同一事件）')
  viewingClosed.value = null
  await refreshAll()
}

function openCheck(row, result) {
  checkForm.value = {
    checkId: row.id,
    kartNo: row.kartNo,
    driverName: row.driverName,
    result,
    resultName: result === 'CONTINUE' ? '可继续' : result === 'RETIRE' ? '退出比赛' : '需要维修',
    note: '',
    version: row.version
  }
  checkDialog.value = true
}

async function submitCheck() {
  if (checkForm.value.result === 'REPAIR' && !checkForm.value.note.trim()) {
    ElMessage.warning('送修必须填写故障描述')
    return
  }
  await redFlagApi.submitCheck(event.value.id, checkForm.value.checkId, {
    result: checkForm.value.result,
    note: checkForm.value.note.trim(),
    version: checkForm.value.version
  })
  checkDialog.value = false
  ElMessage.success('核查结论已提交')
  await refreshAll()
}

async function adjudicate(row, decision) {
  const label = decision === 'CONFIRMED' ? '确认有效（计入最快圈/个人最佳）' : '作废（永不入榜）'
  try {
    await ElMessageBox.confirm(`将该圈速（${row.lapTimeText}）裁定为「${label}」？`, '主管裁定', {
      confirmButtonText: '确认裁定',
      cancelButtonText: '取消',
      type: decision === 'CONFIRMED' ? 'success' : 'warning'
    })
  } catch (e) {
    return
  }
  await redFlagApi.adjudicate(event.value.id, row.id, decision)
  ElMessage.success('圈速已裁定')
  await refreshAll()
}

function openResolve(kind) {
  resolveForm.value = { kind, note: '' }
  resolveDialog.value = true
}

async function submitResolve() {
  const payload = { version: event.value.version, note: resolveForm.value.note.trim() }
  if (resolveForm.value.kind === 'resume') {
    await redFlagApi.resume(event.value.id, payload)
    ElMessage.success('比赛已恢复')
  } else {
    await redFlagApi.terminate(event.value.id, payload)
    ElMessage.success('场次已强制终止')
  }
  resolveDialog.value = false
  viewingClosed.value = event.value.id
  await refreshAll()
}

async function viewEvent(id) {
  viewingClosed.value = id
  event.value = await redFlagApi.detail(id)
  await track.reloadSessions()
  await track.refreshOverview()
}

async function refreshAll() {
  await loadEvent()
  await track.reloadSessions()
  await track.refreshOverview()
}

watch(() => track.sessionId.value, async () => {
  viewingClosed.value = null
  await loadEvent()
})

onMounted(async () => {
  await loadEvent()
  // 处置台每 4 秒拉一次：红旗持续时间、其他值班员的核查、主管裁定实时可见
  pollId = setInterval(loadEvent, 4000)
})

onBeforeUnmount(() => clearInterval(pollId))
</script>

<style scoped>
.rf-idle {
  display: flex;
  flex-direction: column;
  gap: 8px;
}
.rf-guard {
  display: flex;
  gap: 20px;
  align-items: flex-start;
  flex-wrap: wrap;
}
.rf-rules {
  margin: 0;
  padding-left: 18px;
  font-size: 13px;
  color: var(--kt-text-sub);
}
.rf-rules li.ok {
  color: var(--kt-ok);
  font-weight: 600;
}
.rf-rules li.muted {
  list-style: none;
  margin-left: -18px;
  margin-top: 4px;
  font-size: 12px;
}
.rf-closed-panel {
  display: flex;
  gap: 8px;
  align-items: center;
  font-size: 13px;
  color: var(--kt-text-sub);
  background: #f7f9fb;
}
</style>
