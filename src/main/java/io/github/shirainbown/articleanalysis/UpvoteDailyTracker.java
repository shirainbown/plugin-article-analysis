package io.github.shirainbown.articleanalysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.apache.commons.lang3.StringUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;

/**
 * 每日点赞轮询器：Halo 的 UpvotedEvent 不带 @SharedEvent，插件收不到点赞事件，
 * 只能周期性比对文章 Counter 快照，把净点赞增量归属到当日（由 {@link UpvoteDailyStore} 落库）。
 * 数据从插件部署后开始积累，部署前的历史点赞无法按日追溯。
 *
 * <p>轮询间隔默认 {@value #DEFAULT_PERIOD_SECONDS} 秒，可在插件设置
 * {@code upvote.pollIntervalSeconds} 调整（{@value #MIN_PERIOD_SECONDS}–
 * {@value #MAX_PERIOD_SECONDS} 秒），修改后需重启插件生效。
 */
@Component
public class UpvoteDailyTracker {

    private static final Logger log = LoggerFactory.getLogger(UpvoteDailyTracker.class);

    private static final long INITIAL_DELAY_SECONDS = 10;

    static final int DEFAULT_PERIOD_SECONDS = 300;

    static final int MIN_PERIOD_SECONDS = 60;

    static final int MAX_PERIOD_SECONDS = 3600;

    private final UpvoteDailyStore store;

    private final ReactiveExtensionClient client;

    private final PluginContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    private ScheduledExecutorService executor;

    public UpvoteDailyTracker(UpvoteDailyStore store, ReactiveExtensionClient client,
                              PluginContext context) {
        this.store = store;
        this.client = client;
        this.context = context;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "article-analysis-upvote-tracker");
            thread.setDaemon(true);
            return thread;
        });
        int periodSeconds = resolvePeriodSeconds();
        executor.scheduleWithFixedDelay(this::pollSafely,
            INITIAL_DELAY_SECONDS, periodSeconds, TimeUnit.SECONDS);
        log.info("[ArticleAnalysis] 每日点赞轮询已启动，间隔 {} 秒", periodSeconds);
    }

    @PreDestroy
    void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    private void pollSafely() {
        try {
            store.pollFromCounters().block(Duration.ofSeconds(30));
        } catch (Exception e) {
            log.warn("[ArticleAnalysis] 每日点赞轮询异常: {}", e.toString());
        }
    }

    /**
     * 读取插件设置 upvote.pollIntervalSeconds；未配置或非法时用默认值并夹取到合法区间。
     */
    private int resolvePeriodSeconds() {
        try {
            ConfigMap cm = client.fetch(ConfigMap.class, context.getConfigMapName())
                .block(Duration.ofSeconds(10));
            String raw = cm == null || cm.getData() == null ? null : cm.getData().get("upvote");
            if (StringUtils.isNotBlank(raw)) {
                JsonNode node = objectMapper.readTree(raw);
                int configured = node.path("pollIntervalSeconds").asInt(DEFAULT_PERIOD_SECONDS);
                return Math.min(Math.max(configured, MIN_PERIOD_SECONDS), MAX_PERIOD_SECONDS);
            }
        } catch (Exception e) {
            log.warn("[ArticleAnalysis] 读取轮询间隔设置失败，使用默认值 {} 秒: {}",
                DEFAULT_PERIOD_SECONDS, e.toString());
        }
        return DEFAULT_PERIOD_SECONDS;
    }
}
