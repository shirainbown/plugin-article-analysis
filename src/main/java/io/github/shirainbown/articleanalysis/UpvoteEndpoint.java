package io.github.shirainbown.articleanalysis;

import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.GroupVersion;

/**
 * 每日点赞统计查询端点。
 *
 * <p>GET /apis/api.article-analysis.io.github.shirainbown/v1alpha1/upvotes/daily?days=90
 * → {daily: {"2026-07-31": {total: 3, posts: {&lt;postName&gt;: 2}}}}
 * days 可选，默认 90，最大 365，服务端只返回最近 N 天的数据。
 *
 * <p>DELETE /apis/api.article-analysis.io.github.shirainbown/v1alpha1/upvotes/daily
 * → 清空全部每日点赞统计数据（下一轮轮询重建快照）。
 */
@Component
public class UpvoteEndpoint implements CustomEndpoint {

    private final UpvoteDailyStore store;

    public UpvoteEndpoint(UpvoteDailyStore store) {
        this.store = store;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.article-analysis.io.github.shirainbown/v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("upvotes/daily", this::getDaily)
            .DELETE("upvotes/daily", this::resetDaily)
            .build();
    }

    private Mono<ServerResponse> getDaily(ServerRequest request) {
        int days = request.queryParam("days").map(v -> {
            try {
                return Integer.parseInt(v);
            } catch (NumberFormatException e) {
                return UpvoteDailyStore.DEFAULT_RETENTION_DAYS;
            }
        }).orElse(UpvoteDailyStore.DEFAULT_RETENTION_DAYS);
        return store.daily(days).flatMap(body -> ServerResponse.ok().bodyValue(body));
    }

    private Mono<ServerResponse> resetDaily(ServerRequest request) {
        return store.reset().then(ServerResponse.noContent().build());
    }
}
