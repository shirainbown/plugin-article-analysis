<script setup lang="ts">
import { axiosInstance } from '@halo-dev/api-client';
import type { ListedPost } from '@halo-dev/api-client';
import {
  IconArrowDownLine,
  IconArrowUpDownLine,
  IconArrowUpLine,
  IconClose,
  IconExternalLinkLine,
  IconRefreshLine,
  IconSearch,
  Toast,
  VButton,
  VCard,
  VEmpty,
  VLoading,
  VPageHeader,
  VTag,
} from '@halo-dev/components';
import dayjs from 'dayjs';
import { computed, onMounted, ref } from 'vue';

interface PostRow {
  name: string;
  title: string;
  phase: string;
  permalink: string;
  cover: string;
  categories: string[];
  visit: number;
  upvote: number;
  approvedComment: number;
  totalComment: number;
  publishTime: string;
}

const loading = ref(false);
const posts = ref<PostRow[]>([]);

// 筛选
const keyword = ref('');
const phaseFilter = ref<'ALL' | 'PUBLISHED' | 'DRAFT' | 'PENDING_APPROVAL'>('ALL');
const categoryFilter = ref('ALL');

// 排序
type SortKey = 'visit' | 'upvote' | 'approvedComment' | 'publishTime' | 'title';
const sortKey = ref<SortKey>('publishTime');
const sortAsc = ref(false);

// 分页
const page = ref(1);
const pageSize = ref(10);

function mapPost(item: ListedPost): PostRow {
  const post = item.post;
  return {
    name: post.metadata.name,
    title: post.spec.title || '(无标题)',
    phase: post.status?.phase || 'DRAFT',
    permalink: post.status?.permalink || '',
    cover: post.spec.cover || '',
    categories: (item.categories || []).map((c) => c.spec.displayName),
    visit: item.stats?.visit ?? 0,
    upvote: item.stats?.upvote ?? 0,
    approvedComment: item.stats?.approvedComment ?? 0,
    totalComment: item.stats?.totalComment ?? 0,
    publishTime: post.spec.publishTime || post.metadata.creationTimestamp || '',
  };
}

async function fetchAllPosts() {
  loading.value = true;
  try {
    const all: PostRow[] = [];
    // console posts API 页码从 1 开始（page=0 会被当作 1 导致重复）
    let current = 1;
    const size = 50;
    for (;;) {
      const { data } = await axiosInstance.get(
        '/apis/api.console.halo.run/v1alpha1/posts',
        {
          params: {
            page: current,
            size,
            labelSelector: ['content.halo.run/deleted=false'],
          },
        }
      );
      const items: ListedPost[] = data.items || [];
      all.push(...items.map(mapPost));
      if (data.last || items.length === 0) {
        break;
      }
      current += 1;
    }
    posts.value = all;
  } catch (e) {
    console.error('获取文章数据失败', e);
    Toast.error('获取文章数据失败，请检查是否有文章查看权限');
  } finally {
    loading.value = false;
  }
}

const allCategories = computed(() => {
  const set = new Set<string>();
  posts.value.forEach((p) => p.categories.forEach((c) => set.add(c)));
  return [...set].sort((a, b) => a.localeCompare(b, 'zh-CN'));
});

// 状态页签（带计数）
const phaseTabs = computed(() => {
  const count = (phase: string) => posts.value.filter((p) => p.phase === phase).length;
  return [
    { key: 'ALL' as const, label: '全部', count: posts.value.length },
    { key: 'PUBLISHED' as const, label: '已发布', count: count('PUBLISHED') },
    { key: 'DRAFT' as const, label: '草稿', count: count('DRAFT') },
    { key: 'PENDING_APPROVAL' as const, label: '待审核', count: count('PENDING_APPROVAL') },
  ];
});

const filtered = computed(() => {
  const kw = keyword.value.trim().toLowerCase();
  return posts.value.filter((p) => {
    if (kw && !p.title.toLowerCase().includes(kw)) return false;
    if (phaseFilter.value !== 'ALL' && p.phase !== phaseFilter.value) return false;
    if (categoryFilter.value !== 'ALL' && !p.categories.includes(categoryFilter.value)) return false;
    return true;
  });
});

const sorted = computed(() => {
  const arr = [...filtered.value];
  const dir = sortAsc.value ? 1 : -1;
  arr.sort((a, b) => {
    const key = sortKey.value;
    if (key === 'title') return a.title.localeCompare(b.title, 'zh-CN') * dir;
    if (key === 'publishTime') return (a.publishTime < b.publishTime ? -1 : 1) * dir;
    return (a[key] - b[key]) * dir;
  });
  return arr;
});

const paged = computed(() => {
  const start = (page.value - 1) * pageSize.value;
  return sorted.value.slice(start, start + pageSize.value);
});

const totalPages = computed(() => Math.max(1, Math.ceil(sorted.value.length / pageSize.value)));

function changePage(delta: number) {
  page.value = Math.min(totalPages.value, Math.max(1, page.value + delta));
}

function resetPage() {
  page.value = 1;
}

function toggleSort(key: SortKey) {
  if (sortKey.value === key) {
    sortAsc.value = !sortAsc.value;
  } else {
    sortKey.value = key;
    // 数值类默认降序，标题默认升序
    sortAsc.value = key === 'title';
  }
  resetPage();
}

// 汇总卡片
const summary = computed(() => ({
  count: posts.value.length,
  visit: posts.value.reduce((s, p) => s + p.visit, 0),
  comment: posts.value.reduce((s, p) => s + p.approvedComment, 0),
  upvote: posts.value.reduce((s, p) => s + p.upvote, 0),
}));

const phaseLabels: Record<string, { text: string; theme: string }> = {
  PUBLISHED: { text: '已发布', theme: 'success' },
  DRAFT: { text: '草稿', theme: 'default' },
  PENDING_APPROVAL: { text: '待审核', theme: 'warning' },
  FAILED: { text: '发布失败', theme: 'danger' },
};

function phaseLabel(phase: string) {
  return phaseLabels[phase] || { text: phase, theme: 'default' };
}

function formatTime(t: string) {
  return t ? dayjs(t).format('YYYY-MM-DD HH:mm') : '-';
}

function editorLink(name: string) {
  return `/console/posts/editor?name=${name}`;
}

// ==================== 数据详情抽屉 ====================
interface TrendPoint {
  x: string;
  y: number;
}

const drawerPost = ref<PostRow | null>(null);
const rangeMode = ref<'7' | '30' | '90' | 'custom'>('30');
const customStart = ref(dayjs().subtract(30, 'day').format('YYYY-MM-DD'));
const customEnd = ref(dayjs().format('YYYY-MM-DD'));
const trendLoading = ref(false);
const trendSeries = ref<TrendPoint[]>([]);
const sessionSeries = ref<TrendPoint[]>([]);
const trendStats = ref<Record<string, unknown> | null>(null);
const trendError = ref('');
const umamiConfigured = ref(true);
const drawerView = ref<'chart' | 'list'>('chart');

function permalinkPath(permalink: string) {
  try {
    return new URL(permalink, location.origin).pathname;
  } catch {
    return permalink;
  }
}

function openDrawer(p: PostRow) {
  drawerPost.value = p;
  rangeMode.value = '30';
  drawerView.value = 'chart';
  fetchTrend();
}

function closeDrawer() {
  drawerPost.value = null;
}

function setRangeMode(mode: '7' | '30' | '90' | 'custom') {
  rangeMode.value = mode;
  if (mode !== 'custom') {
    fetchTrend();
  }
}

function applyCustomRange() {
  if (!customStart.value || !customEnd.value) {
    Toast.warning('请选择开始和结束日期');
    return;
  }
  if (dayjs(customStart.value).isAfter(dayjs(customEnd.value))) {
    Toast.warning('开始日期不能晚于结束日期');
    return;
  }
  fetchTrend();
}

function currentRange(): { startAt: number; endAt: number } {
  if (rangeMode.value === 'custom') {
    return {
      startAt: dayjs(customStart.value).startOf('day').valueOf(),
      endAt: dayjs(customEnd.value).endOf('day').valueOf(),
    };
  }
  const endAt = Date.now();
  return { startAt: endAt - Number(rangeMode.value) * 86_400_000, endAt };
}

async function fetchTrend() {
  const p = drawerPost.value;
  if (!p) return;
  if (!p.permalink) {
    trendSeries.value = [];
    sessionSeries.value = [];
    trendStats.value = null;
    return;
  }
  trendLoading.value = true;
  trendError.value = '';
  try {
    const { startAt, endAt } = currentRange();
    const { data } = await axiosInstance.get(
      '/apis/api.article-analysis.run.halo/v1alpha1/umami/pageviews',
      { params: { url: permalinkPath(p.permalink), startAt, endAt } }
    );
    if (!data.configured) {
      umamiConfigured.value = false;
      trendSeries.value = [];
      sessionSeries.value = [];
      trendStats.value = null;
      return;
    }
    umamiConfigured.value = true;
    if (data.error) {
      trendError.value = String(data.error);
    }
    trendSeries.value = data.pageviews?.pageviews || [];
    sessionSeries.value = data.pageviews?.sessions || [];
    trendStats.value = data.stats || null;
  } catch (e) {
    console.error('趋势数据加载失败', e);
    trendError.value = '趋势数据加载失败';
  } finally {
    trendLoading.value = false;
  }
}

const trendTotal = computed(() => trendSeries.value.reduce((s, i) => s + i.y, 0));
const trendMax = computed(() => Math.max(0, ...trendSeries.value.map((i) => i.y)));

// Umami stats 数值兼容（部分版本返回 {value, prev} 对象）
function numFromStats(s: Record<string, unknown> | null, key: string): number {
  if (!s) return 0;
  const v = s[key] as { value?: number } | number | undefined;
  if (v == null) return 0;
  return typeof v === 'object' ? (v.value ?? 0) : v;
}

function statValue(key: string): number {
  return numFromStats(trendStats.value, key);
}

function bounceRateOf(stats: Record<string, unknown> | null): string {
  const visits = numFromStats(stats, 'visits');
  if (!visits) return '-';
  return ((numFromStats(stats, 'bounces') / visits) * 100).toFixed(1) + '%';
}

function avgDurationOf(stats: Record<string, unknown> | null): string {
  const visits = numFromStats(stats, 'visits');
  if (!visits) return '-';
  const sec = Math.round(numFromStats(stats, 'totaltime') / visits);
  const m = Math.floor(sec / 60);
  const s = sec % 60;
  return m > 0 ? `${m}分${s}秒` : `${s}秒`;
}

const bounceRate = computed(() => bounceRateOf(trendStats.value));

const avgDuration = computed(() => avgDurationOf(trendStats.value));

// 图表
const CHART_W = 600;
const CHART_H = 260;
const CHART_PAD = 12;

const chartPoints = computed(() => {
  const arr = trendSeries.value;
  if (!arr.length) return '';
  const max = Math.max(...arr.map((i) => i.y), 1);
  const step = (CHART_W - CHART_PAD * 2) / Math.max(arr.length - 1, 1);
  return arr
    .map(
      (i, idx) =>
        `${(CHART_PAD + idx * step).toFixed(1)},${(CHART_H - CHART_PAD - (i.y / max) * (CHART_H - CHART_PAD * 2)).toFixed(1)}`
    )
    .join(' ');
});

const chartArea = computed(() => {
  if (!chartPoints.value) return '';
  const first = chartPoints.value.split(' ')[0].split(',')[0];
  const last = chartPoints.value.split(' ').pop()!.split(',')[0];
  return `${first},${CHART_H - CHART_PAD} ${chartPoints.value} ${last},${CHART_H - CHART_PAD}`;
});

// ==================== 全站作品数据视图（趋势图 / 数据列表） ====================
type MainView = 'trend' | 'daily' | 'articles';
const mainView = ref<MainView>('trend');

const siteRangeMode = ref<'7' | '30' | '90' | 'custom'>('7');
const siteCustomStart = ref(dayjs().subtract(7, 'day').format('YYYY-MM-DD'));
const siteCustomEnd = ref(dayjs().format('YYYY-MM-DD'));
const siteLoading = ref(false);
const siteSeries = ref<TrendPoint[]>([]);
const siteSessions = ref<TrendPoint[]>([]);
const siteStats = ref<Record<string, unknown> | null>(null);
const siteError = ref('');
const siteUmamiConfigured = ref(true);

function setSiteRangeMode(mode: '7' | '30' | '90' | 'custom') {
  siteRangeMode.value = mode;
  if (mode !== 'custom') {
    fetchSiteTrend();
  }
}

function applySiteCustomRange() {
  if (!siteCustomStart.value || !siteCustomEnd.value) {
    Toast.warning('请选择开始和结束日期');
    return;
  }
  if (dayjs(siteCustomStart.value).isAfter(dayjs(siteCustomEnd.value))) {
    Toast.warning('开始日期不能晚于结束日期');
    return;
  }
  fetchSiteTrend();
}

function currentSiteRange(): { startAt: number; endAt: number } {
  if (siteRangeMode.value === 'custom') {
    return {
      startAt: dayjs(siteCustomStart.value).startOf('day').valueOf(),
      endAt: dayjs(siteCustomEnd.value).endOf('day').valueOf(),
    };
  }
  const endAt = Date.now();
  return { startAt: endAt - Number(siteRangeMode.value) * 86_400_000, endAt };
}

async function fetchSiteTrend() {
  siteLoading.value = true;
  siteError.value = '';
  try {
    const { startAt, endAt } = currentSiteRange();
    // url 传空 = 全站数据（后端不过滤 path）
    const { data } = await axiosInstance.get(
      '/apis/api.article-analysis.run.halo/v1alpha1/umami/pageviews',
      { params: { url: '', startAt, endAt } }
    );
    if (!data.configured) {
      siteUmamiConfigured.value = false;
      siteSeries.value = [];
      siteSessions.value = [];
      siteStats.value = null;
      return;
    }
    siteUmamiConfigured.value = true;
    if (data.error) {
      siteError.value = String(data.error);
    }
    siteSeries.value = data.pageviews?.pageviews || [];
    siteSessions.value = data.pageviews?.sessions || [];
    siteStats.value = data.stats || null;
  } catch (e) {
    console.error('全站趋势数据加载失败', e);
    siteError.value = '趋势数据加载失败';
  } finally {
    siteLoading.value = false;
  }
}

const siteChartPoints = computed(() => {
  const arr = siteSeries.value;
  if (!arr.length) return '';
  const max = Math.max(...arr.map((i) => i.y), 1);
  const step = (CHART_W - CHART_PAD * 2) / Math.max(arr.length - 1, 1);
  return arr
    .map(
      (i, idx) =>
        `${(CHART_PAD + idx * step).toFixed(1)},${(CHART_H - CHART_PAD - (i.y / max) * (CHART_H - CHART_PAD * 2)).toFixed(1)}`
    )
    .join(' ');
});

const siteChartArea = computed(() => {
  if (!siteChartPoints.value) return '';
  const first = siteChartPoints.value.split(' ')[0].split(',')[0];
  const last = siteChartPoints.value.split(' ').pop()!.split(',')[0];
  return `${first},${CHART_H - CHART_PAD} ${siteChartPoints.value} ${last},${CHART_H - CHART_PAD}`;
});

const siteTrendDates = computed(() => {
  const arr = siteSeries.value;
  if (!arr.length) return { first: '', last: '' };
  return { first: formatAxisDate(arr[0].x), last: formatAxisDate(arr[arr.length - 1].x) };
});

const siteTrendMax = computed(() => Math.max(0, ...siteSeries.value.map((i) => i.y)));

const siteDailyRows = computed(() => {
  const sessions = new Map(siteSessions.value.map((s) => [s.x, s.y]));
  return siteSeries.value
    .map((p) => ({
      date: dayjs(p.x).format('YYYY-MM-DD'),
      views: p.y,
      sessions: sessions.get(p.x) ?? 0,
    }))
    .sort((a, b) => (a.date < b.date ? 1 : -1));
});

function formatAxisDate(x: string) {
  return dayjs(x).format('MM-DD');
}

const trendDates = computed(() => {
  const arr = trendSeries.value;
  if (!arr.length) return { first: '', last: '' };
  return { first: formatAxisDate(arr[0].x), last: formatAxisDate(arr[arr.length - 1].x) };
});

// 数据列表（每日明细，倒序）
const dailyRows = computed(() => {
  const sessions = new Map(sessionSeries.value.map((s) => [s.x, s.y]));
  return trendSeries.value
    .map((p) => ({
      date: dayjs(p.x).format('YYYY-MM-DD'),
      views: p.y,
      sessions: sessions.get(p.x) ?? 0,
    }))
    .sort((a, b) => (a.date < b.date ? 1 : -1));
});

// ==================== 导出 CSV ====================
function csvCell(s: string | number) {
  return '"' + String(s).replace(/"/g, '""') + '"';
}

function exportCsv() {
  const header = '标题,状态,分类,阅读,评论(已审核),评论(总数),点赞,发布时间,链接\n';
  const lines = sorted.value.map((p) =>
    [
      csvCell(p.title),
      csvCell(phaseLabel(p.phase).text),
      csvCell(p.categories.join('、')),
      p.visit,
      p.approvedComment,
      p.totalComment,
      p.upvote,
      csvCell(formatTime(p.publishTime)),
      csvCell(p.permalink),
    ].join(',')
  );
  const blob = new Blob(['﻿' + header + lines.join('\n')], {
    type: 'text/csv;charset=utf-8',
  });
  const a = document.createElement('a');
  a.href = URL.createObjectURL(blob);
  a.download = `文章数据-${dayjs().format('YYYYMMDD-HHmm')}.csv`;
  a.click();
  URL.revokeObjectURL(a.href);
  Toast.success(`已导出 ${lines.length} 篇文章数据`);
}

onMounted(() => {
  fetchAllPosts();
  fetchSiteTrend();
});
</script>
<template>
  <VPageHeader title="文章数据">
    <template #actions>
      <VButton type="secondary" @click="exportCsv">导出数据</VButton>
      <VButton type="secondary" @click="fetchAllPosts">
        <template #icon>
          <IconRefreshLine />
        </template>
        刷新
      </VButton>
    </template>
  </VPageHeader>

  <div class="article-analysis">
    <!-- 汇总卡片 -->
    <div class="summary-cards">
      <div class="summary-card">
        <div class="summary-label">文章总数</div>
        <div class="summary-value">{{ summary.count }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">总阅读量</div>
        <div class="summary-value">{{ summary.visit }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">总评论数</div>
        <div class="summary-value">{{ summary.comment }}</div>
      </div>
      <div class="summary-card">
        <div class="summary-label">总点赞数</div>
        <div class="summary-value">{{ summary.upvote }}</div>
      </div>
    </div>

    <VCard title="文章数据">
      <!-- 视图切换 + 时间范围（对齐 CSDN 作品数据） -->
      <div class="view-bar">
        <div class="view-tabs">
          <button
            class="view-tab"
            :class="{ active: mainView === 'trend' }"
            @click="mainView = 'trend'"
          >
            趋势图
          </button>
          <button
            class="view-tab"
            :class="{ active: mainView === 'daily' }"
            @click="mainView = 'daily'"
          >
            数据列表
          </button>
          <button
            class="view-tab"
            :class="{ active: mainView === 'articles' }"
            @click="mainView = 'articles'"
          >
            单篇文章分析
          </button>
        </div>
        <div v-if="mainView !== 'articles'" class="range-bar">
          <button
            v-for="m in [
              { key: '7', label: '近7天' },
              { key: '30', label: '近30天' },
              { key: '90', label: '近90天' },
            ]"
            :key="m.key"
            class="range-btn"
            :class="{ active: siteRangeMode === m.key }"
            @click="setSiteRangeMode(m.key as '7' | '30' | '90')"
          >
            {{ m.label }}
          </button>
          <button
            class="range-btn"
            :class="{ active: siteRangeMode === 'custom' }"
            @click="setSiteRangeMode('custom')"
          >
            自定义
          </button>
          <template v-if="siteRangeMode === 'custom'">
            <input v-model="siteCustomStart" type="date" class="date-input" />
            <span class="muted">至</span>
            <input v-model="siteCustomEnd" type="date" class="date-input" />
            <VButton size="sm" @click="applySiteCustomRange">查询</VButton>
          </template>
        </div>
      </div>

      <!-- ========== 趋势图视图（全站） ========== -->
      <template v-if="mainView === 'trend'">
        <div v-if="!siteUmamiConfigured" class="trend-hint">
          未配置 Umami。请在「插件 → 文章数据分析 → 设置」中填写 Umami 服务地址、站点
          ID 和 API Key 后查看趋势数据。
        </div>
        <VLoading v-else-if="siteLoading" />
        <div v-else-if="siteError" class="trend-hint">趋势数据加载失败：{{ siteError }}</div>
        <div v-else-if="!siteSeries.length" class="trend-hint">该时间段内暂无访问数据</div>
        <template v-else>
          <div class="period-stats">
            <div class="period-stat">
              <div class="period-stat-value">{{ siteSeries.reduce((s, i) => s + i.y, 0) }}</div>
              <div class="period-stat-label">浏览量</div>
            </div>
            <div class="period-stat">
              <div class="period-stat-value">{{ numFromStats(siteStats, 'visitors') }}</div>
              <div class="period-stat-label">访客数</div>
            </div>
            <div class="period-stat">
              <div class="period-stat-value">{{ numFromStats(siteStats, 'visits') }}</div>
              <div class="period-stat-label">访问次数</div>
            </div>
            <div class="period-stat">
              <div class="period-stat-value">{{ bounceRateOf(siteStats) }}</div>
              <div class="period-stat-label">跳出率</div>
            </div>
            <div class="period-stat">
              <div class="period-stat-value">{{ avgDurationOf(siteStats) }}</div>
              <div class="period-stat-label">平均访问时长</div>
            </div>
          </div>
          <svg class="trend-chart" :viewBox="`0 0 ${CHART_W} ${CHART_H}`">
            <defs>
              <linearGradient id="siteTrendFill" x1="0" y1="0" x2="0" y2="1">
                <stop offset="0%" stop-color="#4ccba0" stop-opacity="0.28" />
                <stop offset="100%" stop-color="#4ccba0" stop-opacity="0.02" />
              </linearGradient>
            </defs>
            <polygon v-if="siteChartArea" :points="siteChartArea" fill="url(#siteTrendFill)" />
            <polyline v-if="siteChartPoints" :points="siteChartPoints" class="trend-line" />
          </svg>
          <div class="trend-axis">
            <span>{{ siteTrendDates.first }}</span>
            <span>峰值 {{ siteTrendMax }}</span>
            <span>{{ siteTrendDates.last }}</span>
          </div>
        </template>
      </template>

      <!-- ========== 数据列表视图（全站按天） ========== -->
      <template v-else-if="mainView === 'daily'">
        <div v-if="!siteUmamiConfigured" class="trend-hint">未配置 Umami。</div>
        <VLoading v-else-if="siteLoading" />
        <div v-else-if="!siteDailyRows.length" class="trend-hint">该时间段内暂无访问数据</div>
        <div v-else class="daily-table-wrapper">
          <table class="daily-table">
            <thead>
              <tr>
                <th>日期</th>
                <th class="col-center">浏览量</th>
                <th class="col-center">访问次数</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="row in siteDailyRows" :key="row.date">
                <td>{{ row.date }}</td>
                <td class="col-center">{{ row.views }}</td>
                <td class="col-center">{{ row.sessions }}</td>
              </tr>
            </tbody>
          </table>
        </div>
      </template>

      <!-- ========== 单篇文章分析视图 ========== -->
      <template v-else>
      <!-- 状态页签 -->
      <div class="phase-tabs">
        <button
          v-for="tab in phaseTabs"
          :key="tab.key"
          class="phase-tab"
          :class="{ active: phaseFilter === tab.key }"
          @click="phaseFilter = tab.key; resetPage()"
        >
          {{ tab.label }}({{ tab.count }})
        </button>
        <div class="toolbar-right">
          <select v-model="categoryFilter" class="filter-select" @change="resetPage">
            <option value="ALL">全部分类</option>
            <option v-for="c in allCategories" :key="c" :value="c">{{ c }}</option>
          </select>
          <div class="search-box">
            <IconSearch class="search-icon" />
            <input
              v-model="keyword"
              class="search-input"
              placeholder="请输入标题关键词"
              @input="resetPage"
            />
          </div>
        </div>
      </div>

      <VLoading v-if="loading" />

      <VEmpty v-else-if="!paged.length" title="没有匹配的文章">
        <template #message>试试调整筛选条件</template>
      </VEmpty>

      <template v-else>
        <div class="table-wrapper">
          <table class="data-table">
            <thead>
              <tr>
                <th class="sortable" @click="toggleSort('title')">
                  文章
                  <IconArrowUpDownLine v-if="sortKey !== 'title'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="col-status col-center">状态</th>
                <th class="col-category col-center">分类</th>
                <th class="col-num col-center sortable" @click="toggleSort('visit')">
                  阅读
                  <IconArrowUpDownLine v-if="sortKey !== 'visit'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="col-num col-center sortable" @click="toggleSort('approvedComment')">
                  评论
                  <IconArrowUpDownLine v-if="sortKey !== 'approvedComment'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="col-num col-center sortable" @click="toggleSort('upvote')">
                  点赞
                  <IconArrowUpDownLine v-if="sortKey !== 'upvote'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="col-time col-center sortable" @click="toggleSort('publishTime')">
                  发布时间
                  <IconArrowUpDownLine v-if="sortKey !== 'publishTime'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="col-action col-center">操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in paged" :key="p.name">
                <td>
                  <div class="title-wrap">
                    <img v-if="p.cover" :src="p.cover" class="cover" alt="" loading="lazy" />
                    <div v-else class="cover cover-placeholder">未设置封面</div>
                    <div class="title-text">
                      <a
                        v-if="p.permalink"
                        :href="p.permalink"
                        target="_blank"
                        class="title-link"
                        :title="p.title"
                        >{{ p.title }}</a
                      >
                      <span v-else class="title-link" :title="p.title">{{ p.title }}</span>
                    </div>
                  </div>
                </td>
                <td class="col-status col-center">
                  <VTag :theme="phaseLabel(p.phase).theme as any">
                    {{ phaseLabel(p.phase).text }}
                  </VTag>
                </td>
                <td class="col-category col-center category-cell">{{ p.categories.join('、') || '-' }}</td>
                <td class="col-num col-center numeric">{{ p.visit }}</td>
                <td class="col-num col-center numeric">
                  {{ p.approvedComment
                  }}<span v-if="p.totalComment !== p.approvedComment" class="muted"
                    >/{{ p.totalComment }}</span
                  >
                </td>
                <td class="col-num col-center numeric">{{ p.upvote }}</td>
                <td class="col-time col-center time-cell">{{ formatTime(p.publishTime) }}</td>
                <td class="col-action col-center action-cell">
                  <a class="action-link" @click="openDrawer(p)">数据</a>
                  <a :href="editorLink(p.name)" class="action-link">编辑</a>
                  <a
                    v-if="p.permalink"
                    :href="p.permalink"
                    target="_blank"
                    class="action-link"
                  >
                    查看
                  </a>
                </td>
              </tr>
            </tbody>
          </table>
        </div>

        <!-- 分页 -->
        <div class="pagination">
          <span class="muted">共 {{ sorted.length }} 篇文章</span>
          <div class="pagination-right">
            <select v-model.number="pageSize" class="filter-select" @change="resetPage">
              <option :value="10">10 条/页</option>
              <option :value="20">20 条/页</option>
              <option :value="50">50 条/页</option>
            </select>
            <VButton size="sm" :disabled="page <= 1" @click="changePage(-1)">上一页</VButton>
            <span class="page-info">{{ page }} / {{ totalPages }}</span>
            <VButton size="sm" :disabled="page >= totalPages" @click="changePage(1)">下一页</VButton>
          </div>
        </div>
      </template>
      </template>
    </VCard>

    <!-- 数据详情抽屉 -->
    <Teleport to="body">
      <div v-if="drawerPost" class="aa-overlay" @click.self="closeDrawer">
        <div class="aa-drawer">
          <div class="drawer-header">
            <div class="drawer-title" :title="drawerPost.title">{{ drawerPost.title }}</div>
            <button class="drawer-close" @click="closeDrawer">
              <IconClose />
            </button>
          </div>

          <div class="drawer-body">
            <div class="drawer-meta">
              <VTag :theme="phaseLabel(drawerPost.phase).theme as any">
                {{ phaseLabel(drawerPost.phase).text }}
              </VTag>
              <span class="muted">发布于 {{ formatTime(drawerPost.publishTime) }}</span>
              <a
                v-if="drawerPost.permalink"
                :href="drawerPost.permalink"
                target="_blank"
                class="action-link"
              >
                查看文章
                <IconExternalLinkLine class="action-icon" />
              </a>
            </div>

            <!-- 发布至今总数据（Halo） -->
            <div class="drawer-section-title">发布至今总数据</div>
            <div class="drawer-stats">
              <div class="drawer-stat drawer-stat-primary">
                <div class="drawer-stat-value">{{ drawerPost.visit }}</div>
                <div class="drawer-stat-label">阅读量</div>
              </div>
              <div class="drawer-stat">
                <div class="drawer-stat-value">{{ drawerPost.approvedComment }}</div>
                <div class="drawer-stat-label">评论量</div>
              </div>
              <div class="drawer-stat">
                <div class="drawer-stat-value">{{ drawerPost.upvote }}</div>
                <div class="drawer-stat-label">点赞量</div>
              </div>
            </div>

            <!-- Umami 趋势 -->
            <div class="drawer-section-title">基础分析（Umami）</div>

            <div v-if="!umamiConfigured" class="trend-hint">
              未配置 Umami。请在「插件 → 文章数据分析 → 设置」中填写 Umami 服务地址、站点
              ID 和 API Key 后查看趋势数据。
            </div>
            <template v-else>
              <!-- 时间范围 -->
              <div class="range-bar">
                <button
                  v-for="m in [
                    { key: '7', label: '近7天' },
                    { key: '30', label: '近30天' },
                    { key: '90', label: '近90天' },
                  ]"
                  :key="m.key"
                  class="range-btn"
                  :class="{ active: rangeMode === m.key }"
                  @click="setRangeMode(m.key as '7' | '30' | '90')"
                >
                  {{ m.label }}
                </button>
                <button
                  class="range-btn"
                  :class="{ active: rangeMode === 'custom' }"
                  @click="setRangeMode('custom')"
                >
                  自定义
                </button>
                <template v-if="rangeMode === 'custom'">
                  <input v-model="customStart" type="date" class="date-input" />
                  <span class="muted">至</span>
                  <input v-model="customEnd" type="date" class="date-input" />
                  <VButton size="sm" @click="applyCustomRange">查询</VButton>
                </template>
              </div>

              <VLoading v-if="trendLoading" />
              <div v-else-if="trendError" class="trend-hint">趋势数据加载失败：{{ trendError }}</div>
              <div v-else-if="!trendSeries.length" class="trend-hint">该时间段内暂无访问数据</div>
              <template v-else>
                <!-- 区间统计 -->
                <div class="period-stats">
                  <div class="period-stat">
                    <div class="period-stat-value">{{ trendTotal }}</div>
                    <div class="period-stat-label">浏览量</div>
                  </div>
                  <div class="period-stat">
                    <div class="period-stat-value">{{ statValue('visitors') }}</div>
                    <div class="period-stat-label">访客数</div>
                  </div>
                  <div class="period-stat">
                    <div class="period-stat-value">{{ statValue('visits') }}</div>
                    <div class="period-stat-label">访问次数</div>
                  </div>
                  <div class="period-stat">
                    <div class="period-stat-value">{{ bounceRate }}</div>
                    <div class="period-stat-label">跳出率</div>
                  </div>
                  <div class="period-stat">
                    <div class="period-stat-value">{{ avgDuration }}</div>
                    <div class="period-stat-label">平均访问时长</div>
                  </div>
                </div>

                <!-- 视图切换 -->
                <div class="view-tabs">
                  <button
                    class="view-tab"
                    :class="{ active: drawerView === 'chart' }"
                    @click="drawerView = 'chart'"
                  >
                    趋势图
                  </button>
                  <button
                    class="view-tab"
                    :class="{ active: drawerView === 'list' }"
                    @click="drawerView = 'list'"
                  >
                    数据列表
                  </button>
                </div>

                <!-- 趋势图 -->
                <template v-if="drawerView === 'chart'">
                  <svg class="trend-chart" :viewBox="`0 0 ${CHART_W} ${CHART_H}`">
                    <defs>
                      <linearGradient id="trendFill" x1="0" y1="0" x2="0" y2="1">
                        <stop offset="0%" stop-color="#4ccba0" stop-opacity="0.28" />
                        <stop offset="100%" stop-color="#4ccba0" stop-opacity="0.02" />
                      </linearGradient>
                    </defs>
                    <polygon v-if="chartArea" :points="chartArea" fill="url(#trendFill)" />
                    <polyline v-if="chartPoints" :points="chartPoints" class="trend-line" />
                  </svg>
                  <div class="trend-axis">
                    <span>{{ trendDates.first }}</span>
                    <span>峰值 {{ trendMax }}</span>
                    <span>{{ trendDates.last }}</span>
                  </div>
                </template>

                <!-- 数据列表 -->
                <div v-else class="daily-table-wrapper">
                  <table class="daily-table">
                    <thead>
                      <tr>
                        <th>日期</th>
                        <th class="col-center">浏览量</th>
                        <th class="col-center">访问次数</th>
                      </tr>
                    </thead>
                    <tbody>
                      <tr v-for="row in dailyRows" :key="row.date">
                        <td>{{ row.date }}</td>
                        <td class="col-center">{{ row.views }}</td>
                        <td class="col-center">{{ row.sessions }}</td>
                      </tr>
                    </tbody>
                  </table>
                </div>
              </template>
            </template>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
<style scoped>
.article-analysis {
  padding: 1.5rem 2rem;
  max-width: 100rem;
  margin: 0 auto;
}

/* ==================== 汇总卡片 ==================== */
.summary-cards {
  display: grid;
  grid-template-columns: repeat(4, 1fr);
  gap: 1.25rem;
  margin-bottom: 1.5rem;
}

.summary-card {
  background: #fff;
  border: 1px solid #f0f0f0;
  border-radius: 0.75rem;
  padding: 1.5rem 1.75rem;
  box-shadow: 0 1px 3px rgb(0 0 0 / 0.05);
}

.summary-label {
  font-size: 0.875rem;
  color: #6b7280;
  margin-bottom: 0.625rem;
}

.summary-value {
  font-size: 2.25rem;
  font-weight: 700;
  color: #111827;
  line-height: 1.2;
  font-variant-numeric: tabular-nums;
}

/* ==================== 视图切换栏 ==================== */
.view-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}

.view-bar .range-bar {
  margin-left: auto;
  margin-bottom: 0;
}

/* ==================== 状态页签 + 工具栏 ==================== */
.phase-tabs {
  display: flex;
  align-items: center;
  gap: 0.25rem;
  border-bottom: 1px solid #e5e7eb;
  margin-bottom: 1rem;
}

.phase-tab {
  padding: 0.75rem 1.125rem;
  font-size: 0.9375rem;
  color: #6b7280;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  margin-bottom: -1px;
  transition: color 0.15s;
}

.phase-tab:hover {
  color: #111827;
}

.phase-tab.active {
  color: #059669;
  border-bottom-color: #4ccba0;
  font-weight: 600;
}

.toolbar-right {
  margin-left: auto;
  display: flex;
  align-items: center;
  gap: 0.5rem;
  padding-bottom: 0.375rem;
}

.search-box {
  position: relative;
  display: flex;
  align-items: center;
}

.search-icon {
  position: absolute;
  left: 0.625rem;
  width: 1rem;
  height: 1rem;
  color: #9ca3af;
  pointer-events: none;
}

.search-input {
  height: 2.375rem;
  width: 17rem;
  padding: 0 0.75rem 0 2.125rem;
  font-size: 0.9375rem;
  background-color: #fff;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
  transition: border-color 0.15s;
}

.search-input:focus {
  border-color: #4ccba0;
}

.filter-select {
  height: 2.375rem;
  padding: 0 0.625rem;
  font-size: 0.9375rem;
  color: #374151;
  background-color: #fff;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
}

/* ==================== 数据表格 ==================== */
.table-wrapper {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9375rem;
}

/* 列宽分配：固定列给足宽度，标题列吃剩余空间 */
.data-table .col-num {
  width: 5rem;
}

.data-table .col-status {
  width: 5.5rem;
}

.data-table .col-category {
  width: 7.5rem;
}

.data-table .col-time {
  width: 10.5rem;
}

.data-table .col-action {
  width: 9.5rem;
}

.data-table th {
  padding: 0.875rem 1rem;
  font-size: 0.875rem;
  font-weight: 500;
  color: #6b7280;
  white-space: nowrap;
  border-bottom: 1px solid #e5e7eb;
  user-select: none;
  text-align: left;
}

.data-table th.col-center {
  text-align: center;
}

.data-table th.sortable {
  cursor: pointer;
}

.data-table th.sortable:hover {
  color: #111827;
}

.sort-icon {
  display: inline-block;
  width: 0.875rem;
  height: 0.875rem;
  vertical-align: -0.125rem;
  color: #d1d5db;
}

.sort-icon.active {
  color: #4ccba0;
}

.data-table td {
  padding: 1rem;
  border-bottom: 1px solid #f3f4f6;
  color: #374151;
  vertical-align: middle;
}

.data-table td.col-center {
  text-align: center;
}

.data-table tbody tr:hover {
  background-color: #f9fafb;
}

.numeric {
  font-variant-numeric: tabular-nums;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 0.75rem;
}

.cover {
  width: 6rem;
  height: 3.75rem;
  border-radius: 0.5rem;
  object-fit: cover;
  flex-shrink: 0;
  background: #f3f4f6;
}

.cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.75rem;
  color: #9ca3af;
}

.title-text {
  min-width: 0;
}

.title-link {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  color: #111827;
  font-size: 0.9375rem;
  font-weight: 500;
  line-height: 1.5;
  text-decoration: none;
  word-break: break-all;
}

a.title-link:hover {
  color: #4ccba0;
}

.category-cell {
  max-width: 8rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time-cell {
  white-space: nowrap;
  color: #6b7280;
  font-size: 0.875rem;
}

.muted {
  color: #9ca3af;
  font-size: 0.8125rem;
}

.action-cell {
  white-space: nowrap;
}

.action-link {
  display: inline-flex;
  align-items: center;
  gap: 0.125rem;
  margin-right: 1rem;
  font-size: 0.875rem;
  color: #4ccba0;
  text-decoration: none;
  cursor: pointer;
}

.action-link:last-child {
  margin-right: 0;
}

.action-link:hover {
  text-decoration: underline;
}

.action-icon {
  width: 0.875rem;
  height: 0.875rem;
}

/* ==================== 分页 ==================== */
.pagination {
  display: flex;
  align-items: center;
  justify-content: space-between;
  margin-top: 1rem;
}

.pagination-right {
  display: flex;
  align-items: center;
  gap: 0.5rem;
}

.page-info {
  font-size: 0.8125rem;
  color: #6b7280;
  min-width: 3.5rem;
  text-align: center;
}

/* ==================== 数据详情抽屉 ==================== */
.aa-overlay {
  position: fixed;
  inset: 0;
  background: rgb(0 0 0 / 0.45);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}

.aa-drawer {
  width: 62rem;
  max-width: 92vw;
  height: 100%;
  background: #fff;
  box-shadow: -4px 0 20px rgb(0 0 0 / 0.12);
  display: flex;
  flex-direction: column;
  animation: drawer-in 0.2s ease-out;
}

@keyframes drawer-in {
  from {
    transform: translateX(2rem);
    opacity: 0;
  }
  to {
    transform: translateX(0);
    opacity: 1;
  }
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1.375rem 2rem;
  border-bottom: 1px solid #e5e7eb;
}

.drawer-title {
  font-size: 1.25rem;
  font-weight: 600;
  color: #111827;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.drawer-close {
  background: none;
  border: none;
  cursor: pointer;
  color: #6b7280;
  width: 1.75rem;
  height: 1.75rem;
  flex-shrink: 0;
}

.drawer-close:hover {
  color: #111827;
}

.drawer-body {
  padding: 1.5rem 2rem;
  overflow-y: auto;
}

.drawer-meta {
  display: flex;
  align-items: center;
  gap: 1rem;
  font-size: 0.9375rem;
}

.drawer-section-title {
  font-size: 1.0625rem;
  font-weight: 600;
  color: #111827;
  margin: 1.75rem 0 1rem;
  padding-left: 0.75rem;
  border-left: 4px solid #4ccba0;
  line-height: 1.2;
}

.drawer-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 1rem;
}

.drawer-stat {
  background: #f9fafb;
  border-radius: 0.75rem;
  padding: 1.5rem 1rem;
  text-align: center;
}

.drawer-stat-value {
  font-size: 2.25rem;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
  line-height: 1.15;
}

.drawer-stat-label {
  margin-top: 0.5rem;
  font-size: 0.875rem;
  color: #6b7280;
}

/* 主指标强调 */
.drawer-stat-primary {
  background: #ecfdf5;
  border: 1px solid #a7f3d0;
}

.drawer-stat-primary .drawer-stat-value {
  color: #059669;
}

/* 时间范围 */
.range-bar {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 0.5rem;
  margin-bottom: 1rem;
}

.range-btn {
  padding: 0.5rem 1.125rem;
  font-size: 0.9375rem;
  color: #6b7280;
  background: #f3f4f6;
  border: none;
  border-radius: 9999px;
  cursor: pointer;
  transition: all 0.15s;
}

.range-btn:hover {
  color: #111827;
}

.range-btn.active {
  color: #fff;
  background: #4ccba0;
}

.date-input {
  height: 2.375rem;
  padding: 0 0.625rem;
  font-size: 0.9375rem;
  color: #374151;
  background: #fff;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
}

.date-input:focus {
  border-color: #4ccba0;
}

/* 区间统计 */
.period-stats {
  display: grid;
  grid-template-columns: repeat(5, 1fr);
  gap: 0.875rem;
  margin-bottom: 1.25rem;
}

.period-stat {
  background: #f9fafb;
  border-radius: 0.75rem;
  padding: 1.125rem 0.5rem;
  text-align: center;
}

.period-stat-value {
  font-size: 1.5rem;
  font-weight: 700;
  color: #111827;
  font-variant-numeric: tabular-nums;
  white-space: nowrap;
  line-height: 1.2;
}

.period-stat-label {
  margin-top: 0.4375rem;
  font-size: 0.8125rem;
  color: #6b7280;
  white-space: nowrap;
}

/* 视图切换 */
.view-tabs {
  display: inline-flex;
  background: #f3f4f6;
  border-radius: 0.5rem;
  padding: 0.25rem;
  gap: 0.25rem;
  margin-bottom: 0.875rem;
}

.view-tab {
  padding: 0.5rem 1.25rem;
  font-size: 0.9375rem;
  color: #6b7280;
  background: none;
  border: none;
  border-radius: 0.375rem;
  cursor: pointer;
}

.view-tab.active {
  color: #111827;
  background: #fff;
  font-weight: 600;
  box-shadow: 0 1px 2px rgb(0 0 0 / 0.08);
}

.trend-hint {
  padding: 2rem 1.25rem;
  font-size: 0.9375rem;
  color: #6b7280;
  background: #f9fafb;
  border-radius: 0.5rem;
  line-height: 1.6;
}

.trend-chart {
  width: 100%;
  background: #f9fafb;
  border-radius: 0.5rem;
}

.trend-line {
  fill: none;
  stroke: #4ccba0;
  stroke-width: 2;
  stroke-linejoin: round;
  stroke-linecap: round;
}

.trend-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 0.5rem;
  font-size: 0.8125rem;
  color: #9ca3af;
}

/* 每日数据列表 */
.daily-table-wrapper {
  max-height: 24rem;
  overflow-y: auto;
  border: 1px solid #f3f4f6;
  border-radius: 0.5rem;
}

.daily-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.9375rem;
}

.daily-table th {
  position: sticky;
  top: 0;
  background: #f9fafb;
  padding: 0.875rem 1.125rem;
  text-align: left;
  font-weight: 500;
  color: #6b7280;
  border-bottom: 1px solid #e5e7eb;
}

.daily-table th.col-center,
.daily-table td.col-center {
  text-align: center;
}

.daily-table td {
  padding: 0.75rem 1.125rem;
  color: #374151;
  border-bottom: 1px solid #f3f4f6;
  font-variant-numeric: tabular-nums;
}

.daily-table tbody tr:hover {
  background-color: #f9fafb;
}
</style>
