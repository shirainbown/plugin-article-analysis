package run.halo.article.analysis;

import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 每日点赞轮询器：Halo 的 UpvotedEvent 不带 @SharedEvent，插件收不到点赞事件，
 * 只能周期性比对文章 Counter 快照，把净点赞增量归属到当日（由 {@link UpvoteDailyStore} 落库）。
 * 数据从插件部署后开始积累，部署前的历史点赞无法按日追溯。
 */
@Component
public class UpvoteDailyTracker {

    private static final Logger log = LoggerFactory.getLogger(UpvoteDailyTracker.class);

    private static final long INITIAL_DELAY_SECONDS = 10;

    private static final long PERIOD_SECONDS = 60;

    private final UpvoteDailyStore store;

    private ScheduledExecutorService executor;

    public UpvoteDailyTracker(UpvoteDailyStore store) {
        this.store = store;
    }

    @PostConstruct
    void start() {
        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread thread = new Thread(r, "article-analysis-upvote-tracker");
            thread.setDaemon(true);
            return thread;
        });
        executor.scheduleWithFixedDelay(this::pollSafely,
            INITIAL_DELAY_SECONDS, PERIOD_SECONDS, TimeUnit.SECONDS);
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
}
