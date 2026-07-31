package run.halo.article.analysis;

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
 * <p>GET /apis/api.article-analysis.run.halo/v1alpha1/upvotes/daily
 * → {daily: {"2026-07-31": {total: 3, posts: {&lt;postName&gt;: 2}}}}
 */
@Component
public class UpvoteEndpoint implements CustomEndpoint {

    private final UpvoteDailyStore store;

    public UpvoteEndpoint(UpvoteDailyStore store) {
        this.store = store;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.article-analysis.run.halo/v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("upvotes/daily", this::getDaily)
            .build();
    }

    private Mono<ServerResponse> getDaily(ServerRequest request) {
        return store.daily().flatMap(body -> ServerResponse.ok().bodyValue(body));
    }
}
