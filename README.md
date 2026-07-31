# plugin-article-analysis

Halo 文章数据分析插件：在 console 后台提供类似 CSDN「内容管理 + 作品数据」的文章数据看板。

## 安装

要求 Halo 2.23+。

1. 从 Releases 下载最新的 `plugin-article-analysis-<version>.jar`（或按下文「构建」自行打包）。
2. Halo console → 左侧「插件」→ 右上角「安装」→ 上传 jar。
3. 启用插件，左侧「内容」下出现「文章数据」菜单即安装成功。

升级：console → 插件 → 「文章数据分析」→「升级」，上传新版本 jar 即可，配置不会丢失。

## 使用

入口：console → 内容 → 文章数据。

- **趋势图 / 数据列表**：全站每日浏览量、访客数、访问次数、跳出率、平均访问时长、点赞量，支持近 7/30/90 天或自定义起止日期（浏览/访问类指标需先配置 Umami，见下文）。
- **单篇文章分析**：全部 / 已发布 / 草稿 / 待审核文章列表，支持关键词搜索、分类筛选、列头排序、分页；行内「数据」打开单篇详情抽屉（累计数据 + Umami 趋势/明细 + 每日点赞），「编辑」跳 console 编辑器，「查看」跳前台页面。
- **导出数据**：右上角导出当前筛选结果为 CSV（带 BOM，Excel 可直接打开）。
- 未配置 Umami 时文章列表、汇总卡片、导出均正常，仅趋势区域提示未配置。

## 功能

### 文章数据页（console → 内容 → 文章数据）

- **汇总卡片**：文章总数、总阅读量、总评论数、总点赞数（Halo 内置统计，累计值）
- **状态页签**：全部 / 已发布 / 草稿 / 待审核（带计数）
- **筛选**：标题关键词搜索、分类下拉
- **表格**：封面缩略图、标题（点击跳前台）、状态、分类、阅读、评论（已审/总数）、点赞、发布时间；列头点击排序；本地分页（10/20/50）
- **操作列**：数据（详情抽屉）、编辑（跳 console 编辑器）、查看（前台链接）
- **导出数据**：当前筛选结果导出 CSV（带 BOM，Excel 兼容）

### 单篇数据详情抽屉

- 发布至今总数据：阅读量 / 评论量 / 点赞量（Halo 统计）
- **基础分析（Umami）**：
  - 时间范围：近 7 天 / 近 30 天 / 近 90 天 / **自定义起止日期**
  - 区间统计：浏览量 / 访客数 / 访问次数 / 跳出率 / 平均访问时长
  - 趋势图：每日浏览量折线图（渐变面积图）
  - 数据列表：按天的浏览量/访问次数明细表（倒序）

数据来源：表格与汇总全部来自 Halo console 核心 API（`/apis/api.console.halo.run/v1alpha1/posts`），趋势数据由插件后端代理 Umami API 获取。

## 每日点赞统计

Halo 只保存文章的累计点赞数（Counter 扩展），不记录每次点赞的时间，且点赞事件（`UpvotedEvent`）未标注 `@SharedEvent`，插件无法监听。本插件采用「轮询快照差值」方案：每 60 秒读取全部文章的累计点赞，与上次快照比对，把增量归属到当日，持久化在专用 ConfigMap（`article-analysis-upvote-daily`）中。

- 趋势图 hover 提示、数据列表、区间统计中均展示「点赞量」（全站视图=全站文章合计，单篇抽屉=该文章）。
- **数据从插件部署后开始积累**，部署前的历史点赞无法按日追溯；插件重启不丢数据（快照持久化），但停用期间的点赞会归入重新启动后的当天。
- 与前台展示口径一致：统计的是原始点赞计数（不扣取消点赞）。
- 统计时区：默认服务器时区，可在「插件 → 文章数据分析 → 设置 → 点赞统计」中修改（如 `Asia/Shanghai`）。

## Umami 配置（可选，用于趋势图）

插件 → 文章数据分析 → 设置 → Umami 统计：

| 字段 | 说明 |
| --- | --- |
| Umami 服务地址 | 如 `https://umami.example.com`（不含末尾斜杠） |
| 站点 ID | Umami 网站详情中的 Website ID（UUID） |
| API Key | Umami 2.13+「设置 → API keys」创建；低版本可留空 |
| 登录用户名/密码 | 未配置 API Key 时，用账密登录换取访问令牌（兼容 Umami 2.13 以下版本） |

不配置 Umami 时其他功能正常，仅抽屉中趋势区域显示未配置提示。

> 注意：Umami 按 URL 过滤的参数名是 `path`（不是 `url`），本插件已适配；
> 趋势数据由后端代理获取，API Key / 账密只存服务端 ConfigMap，不下发浏览器。

## 构建

```bash
# UI（产物直接写入 src/main/resources/console）
pnpm -C ui install && pnpm -C ui build

# 插件 jar（产物 build/libs/plugin-article-analysis-<version>.jar）
JAVA_HOME=<jdk21路径> ./gradlew build -x test
```

## 部署与版本号注意事项

- 通过 console 插件升级端点上传 jar（`POST /apis/api.console.halo.run/v1alpha1/plugins/article-analysis/upgrade`，multipart 字段名 `file`）。
- **每次部署必须递增 `gradle.properties` 的 version**：Halo console 的插件 bundle 缓存按版本号失效，版本不变时浏览器会一直加载旧代码（曾因此排查很久）。
- console 文章列表 API 分页**从 1 开始**（page=0 会被当作 1 造成重复数据）。

## 技术栈

- 后端：Halo Plugin（`run.halo.plugin.devtools` 0.6.2 + platform 2.23.0，Java 21），`CustomEndpoint` 代理 Umami
- 前端：Vue 3 + Rsbuild（`@halo-dev/ui-plugin-bundler-kit`）+ `@halo-dev/components`

## License

MIT
