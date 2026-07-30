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
const drawerPost = ref<PostRow | null>(null);
const trendDays = ref(30);
const trendLoading = ref(false);
const trendSeries = ref<{ x: string; y: number }[]>([]);
const trendStats = ref<Record<string, unknown> | null>(null);
const trendError = ref('');
const umamiConfigured = ref(true);

function permalinkPath(permalink: string) {
  try {
    return new URL(permalink, location.origin).pathname;
  } catch {
    return permalink;
  }
}

function openDrawer(p: PostRow) {
  drawerPost.value = p;
  trendDays.value = 30;
  fetchTrend();
}

function closeDrawer() {
  drawerPost.value = null;
}

function setTrendDays(days: number) {
  trendDays.value = days;
  fetchTrend();
}

async function fetchTrend() {
  const p = drawerPost.value;
  if (!p) return;
  if (!p.permalink) {
    trendSeries.value = [];
    trendStats.value = null;
    return;
  }
  trendLoading.value = true;
  trendError.value = '';
  try {
    const { data } = await axiosInstance.get(
      '/apis/api.article-analysis.run.halo/v1alpha1/umami/pageviews',
      { params: { url: permalinkPath(p.permalink), days: trendDays.value } }
    );
    if (!data.configured) {
      umamiConfigured.value = false;
      trendSeries.value = [];
      trendStats.value = null;
      return;
    }
    umamiConfigured.value = true;
    if (data.error) {
      trendError.value = String(data.error);
    }
    trendSeries.value = (data.pageviews?.pageviews || []).map((i: { x: string; y: number }) => ({
      x: i.x,
      y: i.y,
    }));
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

const CHART_W = 560;
const CHART_H = 160;
const CHART_PAD = 10;

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

const trendDates = computed(() => {
  const arr = trendSeries.value;
  if (!arr.length) return { first: '', last: '' };
  return { first: arr[0].x, last: arr[arr.length - 1].x };
});

// Umami stats 数值兼容（v2 返回 {value, prev} 对象）
function statValue(key: string): number {
  const s = trendStats.value as Record<string, unknown> | null;
  if (!s) return 0;
  const v = s[key] as { value?: number } | number | undefined;
  if (v == null) return 0;
  return typeof v === 'object' ? (v.value ?? 0) : v;
}

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

onMounted(fetchAllPosts);
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
        <div class="summary-value">{{ summary.count }}</div>
        <div class="summary-label">文章总数</div>
      </div>
      <div class="summary-card">
        <div class="summary-value">{{ summary.visit }}</div>
        <div class="summary-label">总阅读量</div>
      </div>
      <div class="summary-card">
        <div class="summary-value">{{ summary.comment }}</div>
        <div class="summary-label">总评论数</div>
      </div>
      <div class="summary-card">
        <div class="summary-value">{{ summary.upvote }}</div>
        <div class="summary-label">总点赞数</div>
      </div>
    </div>

    <VCard title="文章数据">
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
      </div>

      <!-- 工具栏 -->
      <div class="toolbar">
        <div class="search-box">
          <IconSearch class="search-icon" />
          <input
            v-model="keyword"
            class="search-input"
            placeholder="搜索文章标题"
            @input="resetPage"
          />
        </div>
        <select v-model="categoryFilter" class="filter-select" @change="resetPage">
          <option value="ALL">全部分类</option>
          <option v-for="c in allCategories" :key="c" :value="c">{{ c }}</option>
        </select>
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
                <th>状态</th>
                <th>分类</th>
                <th class="sortable numeric" @click="toggleSort('visit')">
                  阅读
                  <IconArrowUpDownLine v-if="sortKey !== 'visit'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="sortable numeric" @click="toggleSort('approvedComment')">
                  评论
                  <IconArrowUpDownLine v-if="sortKey !== 'approvedComment'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="sortable numeric" @click="toggleSort('upvote')">
                  点赞
                  <IconArrowUpDownLine v-if="sortKey !== 'upvote'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th class="sortable" @click="toggleSort('publishTime')">
                  发布时间
                  <IconArrowUpDownLine v-if="sortKey !== 'publishTime'" class="sort-icon" />
                  <IconArrowUpLine v-else-if="sortAsc" class="sort-icon active" />
                  <IconArrowDownLine v-else class="sort-icon active" />
                </th>
                <th>操作</th>
              </tr>
            </thead>
            <tbody>
              <tr v-for="p in paged" :key="p.name">
                <td class="title-cell">
                  <div class="title-wrap">
                    <img v-if="p.cover" :src="p.cover" class="cover" alt="" loading="lazy" />
                    <div v-else class="cover cover-placeholder">无封面</div>
                    <a
                      v-if="p.permalink"
                      :href="p.permalink"
                      target="_blank"
                      class="title-link"
                      :title="p.title"
                      >{{ p.title }}</a
                    >
                    <span v-else :title="p.title">{{ p.title }}</span>
                  </div>
                </td>
                <td>
                  <VTag :theme="phaseLabel(p.phase).theme as any">
                    {{ phaseLabel(p.phase).text }}
                  </VTag>
                </td>
                <td class="category-cell">{{ p.categories.join('、') || '-' }}</td>
                <td class="numeric">{{ p.visit }}</td>
                <td class="numeric">
                  {{ p.approvedComment
                  }}<span v-if="p.totalComment !== p.approvedComment" class="muted"
                    >/{{ p.totalComment }}</span
                  >
                </td>
                <td class="numeric">{{ p.upvote }}</td>
                <td class="time-cell">{{ formatTime(p.publishTime) }}</td>
                <td class="action-cell">
                  <a class="action-link" @click="openDrawer(p)">数据</a>
                  <a :href="editorLink(p.name)" class="action-link">编辑</a>
                  <a
                    v-if="p.permalink"
                    :href="p.permalink"
                    target="_blank"
                    class="action-link"
                  >
                    查看
                    <IconExternalLinkLine class="action-icon" />
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
    </VCard>

    <!-- 数据详情抽屉 -->
    <Teleport to="body">
      <div v-if="drawerPost" class="drawer-overlay" @click.self="closeDrawer">
        <div class="drawer">
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
              <div class="drawer-stat">
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
            <div class="drawer-section-title">
              访问趋势（Umami）
              <div class="trend-range">
                <button
                  class="range-btn"
                  :class="{ active: trendDays === 7 }"
                  @click="setTrendDays(7)"
                >
                  近7天
                </button>
                <button
                  class="range-btn"
                  :class="{ active: trendDays === 30 }"
                  @click="setTrendDays(30)"
                >
                  近30天
                </button>
              </div>
            </div>

            <div v-if="!umamiConfigured" class="trend-hint">
              未配置 Umami。请在「插件 → 文章数据分析 → 设置」中填写 Umami 服务地址、站点
              ID 和 API Key 后查看趋势数据。
            </div>
            <div v-else-if="trendLoading" class="trend-hint">加载中…</div>
            <div v-else-if="trendError" class="trend-hint">趋势数据加载失败：{{ trendError }}</div>
            <div v-else-if="!trendSeries.length" class="trend-hint">该时间段内暂无访问数据</div>
            <template v-else>
              <div class="trend-summary">
                近{{ trendDays }}天浏览 <b>{{ trendTotal }}</b>
                <span class="muted">（访客 {{ statValue('visitors') }} · 访问 {{ statValue('visits') }} 次）</span>
              </div>
              <svg class="trend-chart" :viewBox="`0 0 ${CHART_W} ${CHART_H}`">
                <polygon v-if="chartArea" :points="chartArea" class="trend-area" />
                <polyline v-if="chartPoints" :points="chartPoints" class="trend-line" />
              </svg>
              <div class="trend-axis">
                <span>{{ trendDates.first }}</span>
                <span>峰值 {{ trendMax }}</span>
                <span>{{ trendDates.last }}</span>
              </div>
            </template>
          </div>
        </div>
      </div>
    </Teleport>
  </div>
</template>
<style scoped>
.article-analysis {
  padding: 1rem;
}

.summary-cards {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(10rem, 1fr));
  gap: 1rem;
  margin-bottom: 1rem;
}

.summary-card {
  background: #fff;
  border-radius: 0.5rem;
  padding: 1.25rem;
  text-align: center;
  box-shadow: 0 1px 2px rgb(0 0 0 / 0.05);
}

.summary-value {
  font-size: 1.75rem;
  font-weight: 700;
  color: #111827;
}

.summary-label {
  margin-top: 0.25rem;
  font-size: 0.8125rem;
  color: #6b7280;
}

/* 状态页签 */
.phase-tabs {
  display: flex;
  gap: 0.25rem;
  margin-bottom: 1rem;
  border-bottom: 1px solid #e5e7eb;
}

.phase-tab {
  padding: 0.5rem 0.875rem;
  font-size: 0.875rem;
  color: #6b7280;
  background: none;
  border: none;
  border-bottom: 2px solid transparent;
  cursor: pointer;
  margin-bottom: -1px;
}

.phase-tab:hover {
  color: #111827;
}

.phase-tab.active {
  color: #059669;
  border-bottom-color: #4ccba0;
  font-weight: 600;
}

.toolbar {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 0.5rem;
  margin-bottom: 1rem;
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
  height: 2rem;
  width: 16rem;
  padding: 0 0.625rem 0 2rem;
  font-size: 0.875rem;
  background-color: #fff;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
}

.search-input:focus {
  border-color: #4ccba0;
}

.filter-select {
  height: 2rem;
  padding: 0 0.5rem;
  font-size: 0.875rem;
  background-color: #fff;
  border: 1px solid #d1d5db;
  border-radius: 0.375rem;
  outline: none;
}

.table-wrapper {
  overflow-x: auto;
}

.data-table {
  width: 100%;
  border-collapse: collapse;
  font-size: 0.875rem;
}

.data-table th {
  padding: 0.625rem 0.75rem;
  text-align: left;
  font-weight: 600;
  color: #374151;
  white-space: nowrap;
  border-bottom: 1px solid #e5e7eb;
  user-select: none;
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
  padding: 0.625rem 0.75rem;
  border-bottom: 1px solid #f3f4f6;
  color: #374151;
}

.data-table tbody tr:hover {
  background-color: #f9fafb;
}

.numeric {
  text-align: right;
  font-variant-numeric: tabular-nums;
}

.title-cell {
  max-width: 24rem;
}

.title-wrap {
  display: flex;
  align-items: center;
  gap: 0.625rem;
}

.cover {
  width: 3rem;
  height: 2rem;
  border-radius: 0.25rem;
  object-fit: cover;
  flex-shrink: 0;
  background: #f3f4f6;
}

.cover-placeholder {
  display: flex;
  align-items: center;
  justify-content: center;
  font-size: 0.625rem;
  color: #9ca3af;
}

.title-link {
  color: #111827;
  font-weight: 500;
  text-decoration: none;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.title-link:hover {
  color: #4ccba0;
}

.category-cell {
  max-width: 10rem;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.time-cell {
  white-space: nowrap;
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
  margin-right: 0.75rem;
  color: #4ccba0;
  text-decoration: none;
  cursor: pointer;
}

.action-link:hover {
  text-decoration: underline;
}

.action-icon {
  width: 0.875rem;
  height: 0.875rem;
}

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

/* 数据详情抽屉 */
.drawer-overlay {
  position: fixed;
  inset: 0;
  background: rgb(0 0 0 / 0.4);
  z-index: 1000;
  display: flex;
  justify-content: flex-end;
}

.drawer {
  width: 40rem;
  max-width: 90vw;
  height: 100%;
  background: #fff;
  box-shadow: -4px 0 16px rgb(0 0 0 / 0.1);
  display: flex;
  flex-direction: column;
}

.drawer-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 1rem 1.25rem;
  border-bottom: 1px solid #e5e7eb;
}

.drawer-title {
  font-size: 1rem;
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
  width: 1.5rem;
  height: 1.5rem;
  flex-shrink: 0;
}

.drawer-close:hover {
  color: #111827;
}

.drawer-body {
  padding: 1.25rem;
  overflow-y: auto;
}

.drawer-meta {
  display: flex;
  align-items: center;
  gap: 0.75rem;
  margin-bottom: 1.25rem;
}

.drawer-section-title {
  display: flex;
  align-items: center;
  justify-content: space-between;
  font-size: 0.875rem;
  font-weight: 600;
  color: #111827;
  margin: 1.25rem 0 0.75rem;
}

.drawer-stats {
  display: grid;
  grid-template-columns: repeat(3, 1fr);
  gap: 0.75rem;
}

.drawer-stat {
  background: #f9fafb;
  border-radius: 0.5rem;
  padding: 0.875rem;
  text-align: center;
}

.drawer-stat-value {
  font-size: 1.375rem;
  font-weight: 700;
  color: #111827;
}

.drawer-stat-label {
  margin-top: 0.125rem;
  font-size: 0.75rem;
  color: #6b7280;
}

.trend-range {
  display: flex;
  gap: 0.375rem;
}

.range-btn {
  padding: 0.25rem 0.625rem;
  font-size: 0.75rem;
  color: #6b7280;
  background: #f3f4f6;
  border: none;
  border-radius: 9999px;
  cursor: pointer;
}

.range-btn.active {
  color: #fff;
  background: #4ccba0;
}

.trend-hint {
  padding: 1.5rem 1rem;
  font-size: 0.8125rem;
  color: #6b7280;
  background: #f9fafb;
  border-radius: 0.5rem;
  line-height: 1.6;
}

.trend-summary {
  font-size: 0.8125rem;
  color: #374151;
  margin-bottom: 0.5rem;
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
}

.trend-area {
  fill: rgb(76 203 160 / 0.12);
  stroke: none;
}

.trend-axis {
  display: flex;
  justify-content: space-between;
  margin-top: 0.375rem;
  font-size: 0.6875rem;
  color: #9ca3af;
}
</style>
