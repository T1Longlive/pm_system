<script setup lang="ts">
import { computed, defineComponent, h, nextTick, onMounted, ref } from 'vue'
import type { ReqParticipant, CraftSchedule } from '@/api/requirement'
import { craftLabel } from '@/constants/craft'

// 需求六节点（按状态机顺序）；已取消为旁路终态，不占节点位
const STEP_STATUS = ['PENDING', 'READY', 'DEV', 'TEST', 'ARCHIVING', 'ARCHIVED']

// 节点文案（流程图语义：建立即完成 → 排期 → 并行开发 → 测试 → 归档）
const STEP_LABELS: Record<string, string> = {
  PENDING: '需求建立',
  READY: '排期',
  DEV: '开发',
  TEST: '测试',
  ARCHIVING: '归档',
  ARCHIVED: '已归档'
}

// 开发阶段扇出的工种分支顺序（测试阶段为 QA）
const DEV_CRAFTS = ['FE', 'BE', 'OPS', 'UI']

// 头像背景色板：按用户 id 取模，保证同一用户颜色稳定
const AVATAR_COLORS = ['#409eff', '#67c23a', '#e6a23c', '#f56c6c', '#9a6fe0', '#16b3a3']

// ===== 布局几何 =====
// 串行连接器宽度（给 S 曲线留足弯曲空间，越宽曲线越平缓不陡）
const CONN_W = 56
// 分支卡槽高与间距（卡片实际高度 40，间距 12，pitch=52；几何计算与之保持一致）
const CARD_H = 40
const CARD_GAP = 12

// S 型贝塞尔曲线：两端水平切线、中间平滑弯曲，无直角拐弯（无箭头，靠左→右阅读习惯表达流向）
const sPath = (y0: number, y1: number, w = CONN_W) =>
  `M0,${y0} C${w * 0.5},${y0} ${w * 0.5},${y1} ${w},${y1}`

// 依据「起点/终点中心线集合」生成平滑连接路径：
// - 1 对 1 同轴 → 单条水平直线
// - 1→M（扇出）/ M→1（汇聚）→ 每个配对一条 S 曲线，平滑展开/汇拢，避免直角
const smoothConnect = (srcYs: number[], dstYs: number[]): string[] => {
  const s = srcYs.length
  const d = dstYs.length
  // 同轴直线：两端中心在同一水平线
  if (s === 1 && d === 1 && srcYs[0] === dstYs[0]) {
    return [`M0,${srcYs[0]} H${CONN_W}`]
  }
  const ps: string[] = []
  if (s === 1) {
    dstYs.forEach((y) => ps.push(sPath(srcYs[0], y)))
  } else if (d === 1) {
    srcYs.forEach((y) => ps.push(sPath(y, dstYs[0])))
  } else {
    // M→M（极少见）：两两相连
    srcYs.forEach((y0) => dstYs.forEach((y1) => ps.push(sPath(y0, y1))))
  }
  return ps
}

// ===== 配色（固定语义：黄=进行中 绿=已完成 灰=未开始 红=已取消）=====
const LINE_OFF = '#dee0e3' // 飞书灰-3（边框灰）
const LINE_CURRENT = '#f7ba1e' // 高亮黄（进行中）
const LINE_DONE = '#34c724' // 飞书成功绿
const LINE_W = 2.5

// 连接器组件：给定若干条 path 的 d 字符串与对应状态（逐条线独立着色），绘制细连线
// 注意：此组件经 h() 动态渲染，scoped 样式作用域不会落到 path 上，故描边/填充需用 SVG 表现属性直接给定
const CConn = defineComponent({
  props: {
    paths: { type: Array, default: () => [] },
    states: { type: Array, default: () => [] }, // 每条线一个 'off' | 'current' | 'done'
    hg: { type: Number, required: true }
  },
  setup(props) {
    return () =>
      h('div', { class: 'conn', style: { height: props.hg + 'px' } },
        h('svg', { class: 'csvg', width: CONN_W, height: props.hg, style: { display: 'block' } },
          props.paths.map((d, idx) => {
            const s = props.states[idx] || 'off'
            return h('path', {
              d,
              fill: 'none',
              stroke: s === 'done' ? LINE_DONE : s === 'current' ? LINE_CURRENT : LINE_OFF,
              'stroke-width': LINE_W,
              'stroke-linecap': 'round',
              'stroke-linejoin': 'round'
            })
          })
        )
      )
  }
})

const props = withDefaults(
  defineProps<{
    status: string
    participants?: ReqParticipant[]
    // 各工种排期日期区间（分支卡按日期配色：灰=未开始、黄=排期进行中、绿=已结束）
    craftSchedules?: CraftSchedule[]
    size?: 'mini' | 'full'
  }>(),
  { participants: () => [], craftSchedules: () => [], size: 'full' }
)

// 是否已取消：整体置灰 + 尾部红色终止标记
const isCancelled = computed(() => props.status === 'CANCELLED')

// 今天日期串（yyyy-MM-dd，与排期区间做字典序比较即日期序）
const todayStr = (() => {
  const n = new Date()
  return `${n.getFullYear()}-${String(n.getMonth() + 1).padStart(2, '0')}-${String(n.getDate()).padStart(2, '0')}`
})()

// 工种排期信息与判定
const craftInfo = (craft: string) => props.craftSchedules.find((c) => c.craft === craft)
const started = (c?: CraftSchedule) => !!c && !!c.startDate && c.startDate <= todayStr
const ended = (c?: CraftSchedule) => !!c && !!c.endDate && c.endDate < todayStr
// 开发类工种的排期区间（QA 除外，QA 可与开发并行）
const devInfos = computed(() => DEV_CRAFTS.map(craftInfo).filter((c): c is CraftSchedule => !!c))
const qaInfo = computed(() => craftInfo('QA'))

// 主干各阶段状态（全部由排期情况推导，修改排期/日期即实时反映）：
// 0 需求建立：创建即完成（绿）；
// 1 排期：排期未满→进行中（黄），全部工种排满（READY）→完成（绿）；
// 2 开发 / 3 测试：工种按排期日期 黄(开工)→绿(工期结束)；状态机兜底提前完成场景；
// 4 归档：全部排期（含 QA）结束→进行中（黄），PM 点击归档（ARCHIVED）→完成（绿）；5 已归档：归档后绿
type StageState = 'done' | 'current' | 'future'
const stageState = (i: number): StageState => {
  if (isCancelled.value) return 'future'
  const s = props.status
  if (s === 'ARCHIVED') return 'done'
  if (i === 0) return 'done'
  if (i === 1) return s === 'PENDING' ? 'current' : 'done'
  if (i === 2) {
    if (devInfos.value.length > 0 && devInfos.value.every(ended)) return 'done'
    if (['TEST', 'ARCHIVING'].includes(s)) return 'done'
    return devInfos.value.some(started) ? 'current' : 'future'
  }
  if (i === 3) {
    if (ended(qaInfo.value)) return 'done'
    if (s === 'ARCHIVING') return 'done'
    return started(qaInfo.value) ? 'current' : 'future'
  }
  if (i === 4) {
    const devOver = devInfos.value.length === 0 || devInfos.value.every(ended)
    if (s !== 'PENDING' && devOver && !started(qaInfo.value)) return 'current'
    return s === 'ARCHIVING' ? 'current' : 'future'
  }
  return 'future'
}

// 胶囊节点状态类：done 已完成 / current 当前进行中 / future 未开始
const isDone = (i: number) => stageState(i) === 'done'
const isCurrent = (i: number) => stageState(i) === 'current'
const nodeCls = (i: number) => ({ done: isDone(i), current: isCurrent(i), future: stageState(i) === 'future' })

// 连线状态规则（按两端节点状态查表，取"较高"者：灰 > 绿 > 黄）：
// 任一端未开始（灰）→ 灰（灰色连任意颜色都灰）；无灰时任一端完成（绿）→ 绿（绿连绿、绿连黄都绿）；两端都进行中（黄）→ 黄
type SegState = 'off' | 'current' | 'done'
const linkState = (a: SegState, b: SegState): SegState => {
  if (a === 'off' || b === 'off') return 'off'
  if (a === 'done' || b === 'done') return 'done'
  return 'current'
}
// 主干阶段 i 节点状态 → 连线状态
const stageSeg = (i: number): SegState => {
  const s = stageState(i)
  return s === 'done' ? 'done' : s === 'current' ? 'current' : 'off'
}
// 工种分支卡状态 → 连线状态
const cardSeg = (craft: string): SegState => {
  const s = craftState(craft)
  return s === 'done' ? 'done' : s === 'active' ? 'current' : 'off'
}
// mini 连线类名：与 full 图同规则（两端节点状态取"较高"者：灰 > 绿 > 黄）
const miniLineCls = (i: number): string => {
  const seg = (k: number): SegState => (isDone(k) ? 'done' : isCurrent(k) ? 'current' : 'off')
  const s = linkState(seg(i), seg(i + 1))
  return s === 'done' ? 'done' : s === 'current' ? 'current' : ''
}

// ===== 分支列数据 =====
// 开发分支：按工种分组（仅含有参与人的工种）
const devBranches = computed(() =>
  DEV_CRAFTS.map((craft) => ({
    craft,
    members: props.participants.filter((p) => p.craft === craft)
  })).filter((b) => b.members.length > 0)
)
// 测试分支：QA 工种
const qaBranches = computed(() => {
  const members = props.participants.filter((p) => p.craft === 'QA')
  return members.length ? [{ craft: 'QA', members }] : []
})

// 列高与各卡中心线 y 坐标（相对列顶部）
const colHeight = (n: number) => (n > 0 ? n * CARD_H + (n - 1) * CARD_GAP : 0)
const centersOf = (n: number) => Array.from({ length: n }, (_, i) => i * (CARD_H + CARD_GAP) + CARD_H / 2)

const devH = computed(() => colHeight(devBranches.value.length))
const qaH = computed(() => colHeight(qaBranches.value.length))

// 整行高度：两列取最大（主干节点与连接线均以此定高）
const rowH = computed(() => Math.max(devH.value, qaH.value, 96))

// 水平中轴线 y：全局对称轴，串行节点与各分支列均以它为中心垂直排布
const axisY = computed(() => rowH.value / 2)

// 分支卡中心线（整树坐标）：bcol 在 tree 中垂直居中（顶部偏移 (rowH-colH)/2）后，再加列内偏移
const devCenters = computed(() => centersOf(devBranches.value.length).map((c) => c + (rowH.value - devH.value) / 2))
const qaCenters = computed(() => centersOf(qaBranches.value.length).map((c) => c + (rowH.value - qaH.value) / 2))

// 分支卡状态（日期驱动）：已归档（终态）→ 全部绿；无排期数据/今天未到 → future 灰；
// 今天在排期区间内 → active 黄；今天已过区间末尾 → done 绿
type BranchVisual = 'future' | 'active' | 'done'
const craftState = (craft: string): BranchVisual => {
  if (props.status === 'ARCHIVED') return 'done'
  const cs = craftInfo(craft)
  if (!cs || !cs.startDate || todayStr < cs.startDate) return 'future'
  if (cs.endDate && todayStr > cs.endDate) return 'done'
  return 'active'
}
const craftDone = (craft: string) => craftState(craft) === 'done'

// 主干节点挂载的参与人：待排期挂策划（待开始/待归档的 PM 图标直接写在模板中）
const plannerParticipants = computed(() => props.participants.filter((p) => p.craft === 'PLANNER'))

// ===== 连接器数据（5 段，顺序 = 模板渲染顺序）=====
type ConnD = { paths: string[]; states: SegState[] }

const connectors = computed(() => {
  const dev = devBranches.value.length > 0
  const qa = qaBranches.value.length > 0
  const ax = axisY.value

  // a: 待排期 → 待开始（串行直线；按两端状态查表）
  const a: ConnD = {
    paths: smoothConnect([ax], [ax]),
    states: [linkState(stageSeg(0), stageSeg(1))]
  }
  // b: 待开始 → 开发（扇出到各工种卡：每条线按「排期节点 vs 该卡片」查表；无分支则对 DEV 单节点）
  const b: ConnD = dev
    ? {
        paths: smoothConnect([ax], devCenters.value),
        states: devBranches.value.map((br) => linkState(stageSeg(1), cardSeg(br.craft)))
      }
    : { paths: smoothConnect([ax], [ax]), states: [linkState(stageSeg(1), stageSeg(2))] }
  // c: 开发 → 测试（每个开发卡各自汇聚：按「该开发卡 vs 测试端」查表）
  let c: ConnD
  if (dev) {
    // 开发各分支汇聚到测试（QA 有卡则入第一张卡中心；无 QA 则入 Test 单节点轴）
    const dst = qa ? qaCenters.value[0] : ax
    const testSeg: SegState = qa ? cardSeg(qaBranches.value[0].craft) : stageSeg(3)
    c = {
      paths: smoothConnect(devCenters.value, [dst]),
      states: devBranches.value.map((br) => linkState(cardSeg(br.craft), testSeg))
    }
  } else if (qa) {
    c = {
      paths: smoothConnect([ax], qaCenters.value),
      states: qaBranches.value.map((br) => linkState(stageSeg(2), cardSeg(br.craft)))
    }
  } else {
    c = { paths: smoothConnect([ax], [ax]), states: [linkState(stageSeg(2), stageSeg(3))] }
  }
  // d: 测试 → 待归档（QA 卡汇聚：按「该 QA 卡 vs 归档节点」查表）
  const d: ConnD = qa
    ? {
        paths: smoothConnect(qaCenters.value, [ax]),
        states: qaBranches.value.map((br) => linkState(cardSeg(br.craft), stageSeg(4)))
      }
    : { paths: smoothConnect([ax], [ax]), states: [linkState(stageSeg(3), stageSeg(4))] }
  // e: 待归档 → 已归档（串行直线；按两端状态查表）
  const e: ConnD = {
    paths: smoothConnect([ax], [ax]),
    states: [linkState(stageSeg(4), stageSeg(5))]
  }
  return { a, b, c, d, e }
})

// 文字头像取姓名末字
const lastName = (name: string) => name.slice(-1)
// 头像背景色（按用户 id 稳定取色）
const avatarColor = (userId: number) => AVATAR_COLORS[userId % AVATAR_COLORS.length]

// 树容器引用：挂载后自动滚动到当前节点（流程图超宽时免拖滚动条）
const treeRef = ref<HTMLElement>()
onMounted(async () => {
  if (props.size !== 'full') return
  await nextTick()
  const current = treeRef.value?.querySelector('.current')
  current?.scrollIntoView({ inline: 'center', block: 'nearest', behavior: 'smooth' })
})
</script>

<template>
  <!-- mini：小圆点连线，行内使用（列表页） -->
  <div v-if="size === 'mini'" class="steps-mini">
    <template v-for="(node, i) in STEP_STATUS" :key="node">
      <span class="mini-dot" :class="{ done: isDone(i), current: isCurrent(i) }" />
      <!-- 连线与 full 图同规则：两端圆点状态取低者（灰 > 黄 > 绿） -->
      <span
        v-if="i < STEP_STATUS.length - 1"
        class="mini-line"
        :class="miniLineCls(i)"
      />
    </template>
    <!-- 已取消：尾部红色标记 -->
    <span v-if="isCancelled" class="mini-cancel">已取消</span>
  </div>

  <!-- full：横向分层节点流程（S 曲线连接） -->
  <div v-else ref="treeRef" class="tree" :class="{ cancelled: isCancelled }">
    <!-- 待排期（策划） -->
    <div class="tnode">
      <div class="capsule" :class="nodeCls(0)">
        <span class="c-dot" />
        <span class="c-label">{{ STEP_LABELS.PENDING }}</span>
        <div class="c-avs">
          <el-tooltip
            v-for="p in plannerParticipants"
            :key="p.userId"
            :content="`${p.realName}（${craftLabel(p.craft)}）`"
            placement="top"
          >
            <div class="avatar-item">
              <el-avatar :size="20" class="avatar" :style="{ backgroundColor: avatarColor(p.userId) }">
                {{ lastName(p.realName) }}
              </el-avatar>
              <span v-if="isDone(0)" class="avatar-check"><el-icon><Check /></el-icon></span>
            </div>
          </el-tooltip>
        </div>
      </div>
    </div>

    <CConn :paths="connectors.a.paths" :states="connectors.a.states" :hg="rowH" />

    <!-- 待开始（PM） -->
    <div class="tnode">
      <div class="capsule" :class="nodeCls(1)">
        <span class="c-dot" />
        <span class="c-label">{{ STEP_LABELS.READY }}</span>
        <div class="c-avs">
          <el-tooltip content="项目经理" placement="top">
            <div class="avatar-item">
              <el-avatar :size="20" class="avatar pm"><el-icon :size="11"><UserFilled /></el-icon></el-avatar>
              <span v-if="isDone(1)" class="avatar-check"><el-icon><Check /></el-icon></span>
            </div>
          </el-tooltip>
        </div>
      </div>
    </div>

    <CConn :paths="connectors.b.paths" :states="connectors.b.states" :hg="rowH" />

    <!-- ===== 开发阶段 ===== -->
    <template v-if="devBranches.length">
      <div class="bcol" :style="{ height: devH + 'px' }">
        <div v-for="b in devBranches" :key="b.craft" class="bcard" :class="craftState(b.craft)">
          <span class="b-dot" />
          <span class="b-craft">{{ craftLabel(b.craft) }}</span>
          <div class="b-members">
            <el-tooltip
              v-for="m in b.members"
              :key="m.userId"
              :content="`${m.realName}（${craftLabel(m.craft)}）`"
              placement="top"
            >
              <div class="b-member">
                <div class="avatar-item">
                  <el-avatar :size="22" class="avatar" :style="{ backgroundColor: avatarColor(m.userId) }">
                    {{ lastName(m.realName) }}
                  </el-avatar>
                  <span v-if="craftDone(b.craft)" class="avatar-check"><el-icon><Check /></el-icon></span>
                </div>
                <span class="b-name">{{ m.realName }}</span>
              </div>
            </el-tooltip>
          </div>
        </div>
      </div>
    </template>
    <template v-else>
      <div class="tnode">
        <div class="capsule" :class="nodeCls(2)">
          <span class="c-dot" />
          <span class="c-label">{{ STEP_LABELS.DEV }}</span>
        </div>
      </div>
    </template>

    <CConn :paths="connectors.c.paths" :states="connectors.c.states" :hg="rowH" />

    <!-- ===== 测试阶段 ===== -->
    <template v-if="qaBranches.length">
      <div class="bcol" :style="{ height: qaH + 'px' }">
        <div v-for="b in qaBranches" :key="b.craft" class="bcard" :class="craftState(b.craft)">
          <span class="b-dot" />
          <span class="b-craft">{{ craftLabel(b.craft) }}</span>
          <div class="b-members">
            <el-tooltip
              v-for="m in b.members"
              :key="m.userId"
              :content="`${m.realName}（${craftLabel(m.craft)}）`"
              placement="top"
            >
              <div class="b-member">
                <div class="avatar-item">
                  <el-avatar :size="22" class="avatar" :style="{ backgroundColor: avatarColor(m.userId) }">
                    {{ lastName(m.realName) }}
                  </el-avatar>
                  <span v-if="craftDone(b.craft)" class="avatar-check"><el-icon><Check /></el-icon></span>
                </div>
                <span class="b-name">{{ m.realName }}</span>
              </div>
            </el-tooltip>
          </div>
        </div>
      </div>
    </template>
    <template v-else>
      <div class="tnode">
        <div class="capsule" :class="nodeCls(3)">
          <span class="c-dot" />
          <span class="c-label">{{ STEP_LABELS.TEST }}</span>
        </div>
      </div>
    </template>

    <CConn :paths="connectors.d.paths" :states="connectors.d.states" :hg="rowH" />

    <!-- 待归档（PM） -->
    <div class="tnode">
      <div class="capsule" :class="nodeCls(4)">
        <span class="c-dot" />
        <span class="c-label">{{ STEP_LABELS.ARCHIVING }}</span>
        <div class="c-avs">
          <el-tooltip content="项目经理" placement="top">
            <div class="avatar-item">
              <el-avatar :size="20" class="avatar pm"><el-icon :size="11"><UserFilled /></el-icon></el-avatar>
              <span v-if="isDone(4)" class="avatar-check"><el-icon><Check /></el-icon></span>
            </div>
          </el-tooltip>
        </div>
      </div>
    </div>

    <CConn :paths="connectors.e.paths" :states="connectors.e.states" :hg="rowH" />

    <!-- 已归档 -->
    <div class="tnode">
      <div class="capsule" :class="nodeCls(5)">
        <span class="c-dot" />
        <span class="c-label">{{ STEP_LABELS.ARCHIVED }}</span>
      </div>
    </div>

    <!-- 已取消：尾部红色终止标记 -->
    <div v-if="isCancelled" class="cancel-flag">
      <el-icon><CircleCloseFilled /></el-icon>
      <span>已取消</span>
    </div>
  </div>
</template>

<style scoped>
/* ===== mini 迷你进度条：小圆点连线 ===== */
.steps-mini {
  display: inline-flex;
  align-items: center;
}

.mini-dot {
  flex-shrink: 0;
  box-sizing: border-box;
  width: 8px;
  height: 8px;
  border-radius: 50%;
  background: #c9cdd4;
}

.mini-dot.done {
  background: #34c724;
}

.mini-dot.current {
  background: #f7ba1e;
  box-shadow: 0 0 0 3px rgba(247, 186, 30, 0.2);
}

.mini-line {
  width: 12px;
  height: 2.5px;
  background: #dee0e3;
}

.mini-line.done {
  background: #34c724;
}

.mini-line.current {
  background: #f7ba1e;
}

.mini-cancel {
  margin-left: 6px;
  font-size: 12px;
  font-weight: 600;
  color: #f54a45;
}

/* ===== full 横向分层节点流程 ===== */
.tree {
  display: flex;
  align-items: center; /* 各列以水平中轴线（rowH/2）垂直居中，节点多少对称排布 */
  padding: 8px 4px;
  overflow-x: auto;
}

/* 主干节点容器：内容随 tree 垂直居中（胶囊中心落在中轴线）；z-index 高于连接线 */
.tnode {
  position: relative;
  z-index: 1;
  display: flex;
  flex: none;
}

/* 胶囊节点：白底灰虚线边框（未开始），文字居中；左侧状态圆点 */
.capsule {
  display: flex;
  align-items: center;
  position: relative;
  box-sizing: border-box;
  height: 34px;
  padding: 0 14px;
  border: 1px dashed #dee0e3;
  border-radius: 17px;
  background: #fff;
  gap: 6px;
  white-space: nowrap;
}

/* 左侧状态圆点 */
.c-dot {
  flex: none;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #c9cdd4;
}

.c-label {
  font-size: 13px;
  color: #646a73;
}

/* 已完成：飞书绿（成功）——绿实线边 + 绿点 + 浅绿底 + 绿字 */
.capsule.done {
  border: 1px solid #34c724;
  background: #ebf8e8;
}
.capsule.done .c-dot {
  background: #34c724;
}
.capsule.done .c-label {
  font-weight: 600;
  color: #2ea121;
}

/* 当前进行中：高亮黄——黄实线边 + 黄点 + 浅黄底 + 黄字 + 光晕 */
.capsule.current {
  border: 2px solid #f7ba1e;
  background: #fff9e6;
  box-shadow: 0 0 0 3px rgba(247, 186, 30, 0.18);
}
.capsule.current .c-dot {
  background: #f7ba1e;
}
.capsule.current .c-label {
  font-weight: 600;
  color: #ad6800;
}

/* 未开始：白底 + 灰虚线边 + 灰点 + 灰字（飞书三级文字） */
.capsule.future .c-label {
  color: #8f959e;
}

/* 胶囊内的头像区（右侧） */
.c-avs {
  display: flex;
  align-items: center;
  gap: 2px;
  margin-left: 2px;
}

.avatar-item {
  position: relative;
  line-height: 0;
}

.avatar {
  font-size: 11px;
  font-weight: 600;
}

.avatar.pm {
  background-color: #1890ff;
}

.avatar-check {
  position: absolute;
  right: -3px;
  bottom: -3px;
  display: flex;
  align-items: center;
  justify-content: center;
  box-sizing: border-box;
  width: 12px;
  height: 12px;
  border: 1px solid #fff;
  border-radius: 50%;
  background: #52c41a;
  color: #fff;
  font-size: 8px;
}

/* ===== 连接器：SVG 平滑曲线 ===== */
.conn {
  position: relative;
  z-index: 0;
  flex: none;
  width: 56px;
}

/* ===== 分支列 ===== */
.bcol {
  position: relative;
  z-index: 1;
  display: flex;
  flex: none;
  flex-direction: column;
  justify-content: center;
  gap: 12px;
}

/* 分支胶囊卡：白底灰虚线边框（未开始）+ 左侧状态圆点 */
.bcard {
  display: flex;
  align-items: center;
  position: relative;
  box-sizing: border-box;
  height: 40px;
  padding: 0 12px;
  border: 1px dashed #dee0e3;
  border-radius: 20px;
  background: #fff;
  gap: 8px;
}

.b-dot {
  flex: none;
  width: 9px;
  height: 9px;
  border-radius: 50%;
  background: #c9cdd4;
}

/* 排期进行中（今天在该工种排期区间内）：高亮黄——黄框 + 黄点 + 浅黄底 + 光晕 */
.bcard.active {
  border: 2px solid #f7ba1e;
  background: #fff9e6;
  box-shadow: 0 0 0 3px rgba(247, 186, 30, 0.18);
}
.bcard.active .b-dot {
  background: #f7ba1e;
}

/* 已完成阶段：飞书绿——绿框 + 绿点 + 浅绿底 */
.bcard.done {
  border: 1px solid #34c724;
  background: #ebf8e8;
}
.bcard.done .b-dot {
  background: #34c724;
}

/* 未开始：白底灰字灰点（飞书灰阶） */
.bcard.future .b-craft {
  color: #8f959e;
  background: #f2f3f5;
}
.bcard.future .b-name {
  color: #8f959e;
}

/* 工种标签 */
.b-craft {
  flex: none;
  padding: 2px 8px;
  border-radius: 10px;
  background: #f2f3f5;
  color: #646a73;
  font-size: 12px;
  font-weight: 600;
  white-space: nowrap;
}

.bcard.active .b-craft {
  background: #fff1c2;
  color: #ad6800;
}

.bcard.done .b-craft {
  background: #e8f7e2;
  color: #2ea121;
}

/* 卡内成员 */
.b-members {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
}

.b-member {
  display: flex;
  align-items: center;
  gap: 4px;
}

.b-name {
  font-size: 12px;
  color: #606266;
  white-space: nowrap;
}

/* 已取消：整体置灰 */
.tree.cancelled .capsule,
.tree.cancelled .bcard,
.tree.cancelled .c-avs,
.tree.cancelled .b-members {
  opacity: 0.5;
  filter: grayscale(1);
}

.tree.cancelled .avatar-check {
  display: none;
}

/* 尾部红色终止标记（飞书危险红） */
.cancel-flag {
  display: flex;
  flex: none;
  align-items: center;
  align-self: center;
  gap: 4px;
  margin-left: 12px;
  font-size: 13px;
  font-weight: 600;
  color: #f54a45;
  white-space: nowrap;
}
</style>