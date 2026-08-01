package io.github.shirainbown.articleanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.retry.Retry;
import run.halo.app.core.extension.Counter;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.Metadata;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;

/**
 * 每日点赞数存储：Halo 只保存累计点赞（Counter 扩展），不记录每次点赞的时间，
 * 且 UpvotedEvent 未标注 @SharedEvent，插件收不到点赞事件。
 * 因此采用「轮询 Counter 快照差值」方案：周期性读取所有文章的累计点赞，
 * 与上次快照比较，把增量归属到当日。
 *
 * <p>存储格式（ConfigMap {@value #CONFIG_MAP_NAME} 的 data）：
 * <ul>
 *   <li>{@code snap.<postName> → "12"}：上次轮询时的累计净点赞（点赞-取消）</li>
 *   <li>{@code total.2026-07-31 → "3"}：当日全站（文章）点赞总数</li>
 *   <li>{@code posts.2026-07-31.<postName> → "2"}：当日单篇文章点赞数</li>
 * </ul>
 *
 * <p>有界设计（数据生命周期）：
 * <ul>
 *   <li>保留期：按日数据只保留最近 {@code upvote.retentionDays} 天（默认
 *   {@value #DEFAULT_RETENTION_DAYS} 天），每次轮询自动裁剪过期 key；</li>
 *   <li>快照清理：文章删除后对应的 {@code snap.*} key 在下一次轮询移除；</li>
 *   <li>每日上限：每天最多记录 {@value #MAX_POSTS_PER_DAY} 篇文章的明细，
 *   超出部分只累计到全站总数；</li>
 *   <li>清理与卸载：可通过 DELETE upvotes/daily 端点清空全部统计数据，
 *   卸载插件后可手动删除 ConfigMap {@value #CONFIG_MAP_NAME}；</li>
 *   <li>备份：全部统计数据仅存放于上述 ConfigMap，随 Halo 站点备份一并导出。</li>
 * </ul>
 */
@Component
public class UpvoteDailyStore {

    static final String CONFIG_MAP_NAME = "article-analysis-upvote-daily";

    private static final String POST_COUNTER_PREFIX = "posts.content.halo.run/";

    private static final Logger log = LoggerFactory.getLogger(UpvoteDailyStore.class);

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    private static final Pattern POST_KEY = Pattern.compile("^posts\\.(\\d{4}-\\d{2}-\\d{2})\\.(.+)$");

    private static final Pattern TOTAL_KEY = Pattern.compile("^total\\.(\\d{4}-\\d{2}-\\d{2})$");

    static final int DEFAULT_RETENTION_DAYS = 90;

    static final int MIN_RETENTION_DAYS = 7;

    static final int MAX_RETENTION_DAYS = 365;

    /** 每天最多记录明细的文章数，防止 ConfigMap 随文章数无限膨胀。 */
    static final int MAX_POSTS_PER_DAY = 1000;

    private final ReactiveExtensionClient client;

    private final PluginContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public UpvoteDailyStore(ReactiveExtensionClient client, PluginContext context) {
        this.client = client;
        this.context = context;
    }

    /**
     * 轮询一次：读取全部文章 Counter，与快照比对，把点赞增量记入当日。
     * 首次见到某文章只建立快照不记录（避免把历史累计算到当天）。
     * 注意：前台/console 展示的点赞数是原始 upvote 计数（不扣 downvote），这里与其保持一致。
     */
    public Mono<Void> pollFromCounters() {
        return client.list(Counter.class,
                c -> c.getMetadata().getName().startsWith(POST_COUNTER_PREFIX), null)
            .collectList()
            .flatMap(counters -> resolveUpvoteSettings().flatMap(settings -> {
                String date = LocalDate.now(settings.zone()).format(DATE_FMT);
                return applyPoll(counters, date, settings.retentionDays())
                    // 并发（手动刷新/重启瞬间）可能导致乐观锁冲突，退避重试
                    .retryWhen(Retry.backoff(3, Duration.ofMillis(200))
                        .filter(UpvoteDailyStore::isConcurrencyError));
            }));
    }

    private Mono<Void> applyPoll(List<Counter> counters, String date, int retentionDays) {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(cm -> {
                Map<String, String> data = new LinkedHashMap<>();
                if (cm.getData() != null) {
                    data.putAll(cm.getData());
                }
                boolean changed = mergeDeltas(data, counters, date);
                changed |= prune(data, counters, date, retentionDays);
                cm.setData(data);
                return changed ? client.update(cm) : Mono.just(cm);
            })
            .switchIfEmpty(Mono.defer(() -> {
                ConfigMap cm = new ConfigMap();
                Metadata metadata = new Metadata();
                metadata.setName(CONFIG_MAP_NAME);
                cm.setMetadata(metadata);
                Map<String, String> data = new LinkedHashMap<>();
                mergeDeltas(data, counters, date);
                cm.setData(data);
                return client.create(cm);
            }))
            .then();
    }

    /**
     * 把本轮 Counter 与快照的差值并入当日统计，返回数据是否有变化。
     * 单篇明细受 {@link #MAX_POSTS_PER_DAY} 上限约束，超出只记全站总数。
     */
    private static boolean mergeDeltas(Map<String, String> data, List<Counter> counters,
                                       String date) {
        boolean changed = false;
        for (Counter counter : counters) {
            String postName = counter.getMetadata().getName().substring(POST_COUNTER_PREFIX.length());
            int current = Math.max(0, nullToZero(counter.getUpvote()));
            String snapKey = "snap." + postName;
            String snapRaw = data.get(snapKey);
            if (snapRaw == null) {
                // 首次见到：只建立快照，不计入当日（历史数据无法归属）
                data.put(snapKey, String.valueOf(current));
                changed = true;
                continue;
            }
            int delta = current - parseInt(snapRaw);
            if (delta == 0) {
                continue;
            }
            mergeKey(data, "total." + date, delta);
            String postKey = "posts." + date + "." + postName;
            if (data.containsKey(postKey) || countPostEntries(data, date) < MAX_POSTS_PER_DAY) {
                mergeKey(data, postKey, delta);
            }
            data.put(snapKey, String.valueOf(current));
            changed = true;
        }
        return changed;
    }

    /**
     * 有界化裁剪：
     * 1) 移除早于保留期的 {@code total.*} / {@code posts.*} 数据；
     * 2) 移除已删除文章的 {@code snap.*} 快照。
     * ISO 日期字符串可直接按字典序比较。
     */
    private static boolean prune(Map<String, String> data, List<Counter> counters,
                                 String today, int retentionDays) {
        String minDate = LocalDate.parse(today, DATE_FMT)
            .minusDays(retentionDays - 1L).format(DATE_FMT);
        Set<String> alivePosts = new HashSet<>();
        for (Counter counter : counters) {
            alivePosts.add(counter.getMetadata().getName().substring(POST_COUNTER_PREFIX.length()));
        }
        boolean changed = false;
        var iterator = data.entrySet().iterator();
        while (iterator.hasNext()) {
            String key = iterator.next().getKey();
            var totalMatcher = TOTAL_KEY.matcher(key);
            var postMatcher = totalMatcher.matches() ? null : POST_KEY.matcher(key);
            if (totalMatcher.matches()) {
                if (totalMatcher.group(1).compareTo(minDate) < 0) {
                    iterator.remove();
                    changed = true;
                }
                continue;
            }
            if (postMatcher != null && postMatcher.matches()) {
                if (postMatcher.group(1).compareTo(minDate) < 0) {
                    iterator.remove();
                    changed = true;
                }
                continue;
            }
            if (key.startsWith("snap.") && !alivePosts.contains(key.substring("snap.".length()))) {
                iterator.remove();
                changed = true;
            }
        }
        return changed;
    }

    private static int countPostEntries(Map<String, String> data, String date) {
        String prefix = "posts." + date + ".";
        int count = 0;
        for (String key : data.keySet()) {
            if (key.startsWith(prefix)) {
                count++;
            }
        }
        return count;
    }

    private static void mergeKey(Map<String, String> data, String key, int delta) {
        int current = parseInt(data.get(key));
        data.put(key, String.valueOf(Math.max(0, current + delta)));
    }

    /**
     * 读出按日统计（最多最近 {@code days} 天），
     * 组装为 {daily: {"2026-07-31": {total: n, posts: {name: n}}}}。
     */
    public Mono<Map<String, Object>> daily(int days) {
        int boundedDays = Math.min(Math.max(days, 1), MAX_RETENTION_DAYS);
        return resolveUpvoteSettings().flatMap(settings -> {
            String minDate = LocalDate.now(settings.zone())
                .minusDays(boundedDays - 1L).format(DATE_FMT);
            return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
                .map(cm -> assembleDaily(cm, minDate))
                .defaultIfEmpty(Map.<String, Object>of("daily", Map.of()));
        });
    }

    private static Map<String, Object> assembleDaily(ConfigMap cm, String minDate) {
        Map<String, Map<String, Object>> result = new TreeMap<>();
        for (var entry : cm.getData().entrySet()) {
            String key = entry.getKey();
            int count = parseInt(entry.getValue());
            var totalMatcher = TOTAL_KEY.matcher(key);
            if (totalMatcher.matches()) {
                if (totalMatcher.group(1).compareTo(minDate) < 0) {
                    continue;
                }
                day(result, totalMatcher.group(1)).put("total", count);
                continue;
            }
            var postMatcher = POST_KEY.matcher(key);
            if (postMatcher.matches()) {
                if (postMatcher.group(1).compareTo(minDate) < 0) {
                    continue;
                }
                @SuppressWarnings("unchecked")
                var posts = (Map<String, Object>) day(result, postMatcher.group(1))
                    .computeIfAbsent("posts", k -> new TreeMap<String, Object>());
                posts.put(postMatcher.group(2), count);
            }
        }
        return Map.<String, Object>of("daily", result);
    }

    private static Map<String, Object> day(Map<String, Map<String, Object>> days, String date) {
        return days.computeIfAbsent(date, k -> new LinkedHashMap<>());
    }

    /**
     * 清空全部每日点赞统计数据（删除存储 ConfigMap，下一轮轮询会重建快照）。
     */
    public Mono<Void> reset() {
        return client.fetch(ConfigMap.class, CONFIG_MAP_NAME)
            .flatMap(client::delete)
            .then();
    }

    /**
     * 点赞统计设置：时区与保留天数，读取插件设置 upvote 分组。
     * 留空或非法时使用默认值（服务器默认时区、{@link #DEFAULT_RETENTION_DAYS} 天）。
     */
    Mono<UpvoteSettings> resolveUpvoteSettings() {
        return client.fetch(ConfigMap.class, context.getConfigMapName())
            .map(cm -> {
                String raw = cm.getData() == null ? null : cm.getData().get("upvote");
                ZoneId zone = ZoneId.systemDefault();
                int retentionDays = DEFAULT_RETENTION_DAYS;
                if (StringUtils.isNotBlank(raw)) {
                    try {
                        JsonNode node = objectMapper.readTree(raw);
                        String tz = node.path("timezone").asText("");
                        if (StringUtils.isNotBlank(tz)) {
                            zone = ZoneId.of(tz);
                        }
                        retentionDays = clampRetention(node.path("retentionDays").asInt(
                            DEFAULT_RETENTION_DAYS));
                    } catch (Exception e) {
                        // 保留默认值
                    }
                }
                return new UpvoteSettings(zone, retentionDays);
            })
            .defaultIfEmpty(new UpvoteSettings(ZoneId.systemDefault(), DEFAULT_RETENTION_DAYS))
            .onErrorReturn(new UpvoteSettings(ZoneId.systemDefault(), DEFAULT_RETENTION_DAYS));
    }

    static int clampRetention(int days) {
        return Math.min(Math.max(days, MIN_RETENTION_DAYS), MAX_RETENTION_DAYS);
    }

    record UpvoteSettings(ZoneId zone, int retentionDays) {
    }

    private static int nullToZero(Integer v) {
        return v == null ? 0 : v;
    }

    private static int parseInt(String s) {
        try {
            return s == null ? 0 : Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static boolean isConcurrencyError(Throwable e) {
        if (e instanceof OptimisticLockingFailureException) {
            return true;
        }
        String msg = e.getMessage();
        boolean alreadyExists = msg != null && msg.toLowerCase().contains("already exists");
        if (!alreadyExists) {
            log.warn("[ArticleAnalysis] 每日点赞轮询失败（不重试）: {}", e.toString());
        }
        return alreadyExists;
    }
}
