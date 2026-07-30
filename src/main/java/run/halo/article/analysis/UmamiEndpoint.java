package run.halo.article.analysis;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.Map;
import org.apache.commons.lang3.StringUtils;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerRequest;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.core.publisher.Mono;
import run.halo.app.core.extension.endpoint.CustomEndpoint;
import run.halo.app.extension.ConfigMap;
import run.halo.app.extension.GroupVersion;
import run.halo.app.extension.ReactiveExtensionClient;
import run.halo.app.plugin.PluginContext;

/**
 * Umami 数据代理端点：把 Umami API 的按 URL 页面访问数据代理给 console 前端，
 * apiKey 只存在服务端 ConfigMap 中，不下发到浏览器。
 *
 * <p>GET /apis/api.article-analysis.run.halo/v1alpha1/umami/pageviews?url=/archives/xxx&amp;days=30
 */
@Component
public class UmamiEndpoint implements CustomEndpoint {

    private static final Duration TIMEOUT = Duration.ofSeconds(15);

    private final ReactiveExtensionClient client;

    private final PluginContext context;

    private final ObjectMapper objectMapper = new ObjectMapper();

    public UmamiEndpoint(ReactiveExtensionClient client, PluginContext context) {
        this.client = client;
        this.context = context;
    }

    @Override
    public GroupVersion groupVersion() {
        return GroupVersion.parseAPIVersion("api.article-analysis.run.halo/v1alpha1");
    }

    @Override
    public RouterFunction<ServerResponse> endpoint() {
        return RouterFunctions.route()
            .GET("umami/pageviews", this::getPageviews)
            .build();
    }

    private Mono<ServerResponse> getPageviews(ServerRequest request) {
        // url 可选：传入时按文章路径过滤，不传则返回全站数据
        String url = request.queryParam("url").orElse("");
        return loadUmamiConfig().flatMap(cfg -> {
            if (!cfg.configured()) {
                return ServerResponse.ok().bodyValue(Map.of("configured", false));
            }
            // 优先使用 startAt/endAt（毫秒时间戳）自定义范围，否则按 days 推算
            long endAt = request.queryParam("endAt").map(Long::parseLong)
                .orElseGet(System::currentTimeMillis);
            long startAt = request.queryParam("startAt").map(Long::parseLong)
                .orElseGet(() -> {
                    int days = request.queryParam("days").map(v -> {
                        try {
                            return Math.min(Math.max(Integer.parseInt(v), 1), 365);
                        } catch (NumberFormatException e) {
                            return 30;
                        }
                    }).orElse(30);
                    return endAt - days * 86_400_000L;
                });
            Mono<String> pageviews = queryUmami(cfg, "/api/websites/{id}/pageviews", startAt, endAt, url, "day");

            Mono<String> stats = queryUmami(cfg, "/api/websites/{id}/stats", startAt, endAt, url, null);

            return Mono.zip(pageviews, stats)
                // 原始 JSON 字符串直接拼接返回，规避插件类加载器下 Jackson 树模型序列化问题
                .flatMap(tuple -> ServerResponse.ok()
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("{\"configured\":true,\"pageviews\":" + tuple.getT1()
                        + ",\"stats\":" + tuple.getT2() + "}"))
                .onErrorResume(e -> ServerResponse.ok().bodyValue(
                    Map.of("configured", true, "error",
                        StringUtils.defaultString(e.getMessage(), "Umami 请求失败"))));
        });
    }

    /**
     * 调 Umami API：优先使用 API Key；未配置时用账密登录换取令牌。
     */
    private Mono<String> queryUmami(UmamiConfig cfg, String path, long startAt, long endAt,
                                      String url, String unit) {
        return resolveToken(cfg).flatMap(token -> WebClient.builder()
            .baseUrl(cfg.serverUrl())
            .build()
            .get()
            .uri(builder -> {
                var b = builder.path(path)
                    .queryParam("startAt", startAt)
                    .queryParam("endAt", endAt);
                if (StringUtils.isNotBlank(url)) {
                    // Umami 按 URL 过滤的参数名是 path（不是 url）
                    b.queryParam("path", url);
                }
                if (unit != null) {
                    b.queryParam("unit", unit);
                }
                return b.build(cfg.websiteId());
            })
            .header(HttpHeaders.AUTHORIZATION, "Bearer " + token)
            .retrieve()
            .bodyToMono(String.class)
            .timeout(TIMEOUT));
    }

    private Mono<String> resolveToken(UmamiConfig cfg) {
        if (StringUtils.isNotBlank(cfg.apiKey())) {
            return Mono.just(cfg.apiKey());
        }
        return WebClient.builder()
            .baseUrl(cfg.serverUrl())
            .build()
            .post()
            .uri("/api/auth/login")
            .bodyValue(Map.of("username", cfg.username(), "password", cfg.password()))
            .retrieve()
            .bodyToMono(String.class)
            .timeout(TIMEOUT)
            .map(body -> {
                try {
                    return objectMapper.readTree(body).path("token").asText("");
                } catch (Exception e) {
                    return "";
                }
            });
    }

    private Mono<UmamiConfig> loadUmamiConfig() {
        return client.fetch(ConfigMap.class, context.getConfigMapName())
            .map(configMap -> {
                String raw = configMap.getData() == null ? null : configMap.getData().get("umami");
                if (StringUtils.isBlank(raw)) {
                    return UmamiConfig.EMPTY;
                }
                try {
                    JsonNode node = objectMapper.readTree(raw);
                    return new UmamiConfig(
                        StringUtils.defaultString(node.path("serverUrl").asText("")),
                        StringUtils.defaultString(node.path("websiteId").asText("")),
                        StringUtils.defaultString(node.path("apiKey").asText("")),
                        StringUtils.defaultString(node.path("username").asText("")),
                        StringUtils.defaultString(node.path("password").asText("")));
                } catch (Exception e) {
                    return UmamiConfig.EMPTY;
                }
            })
            .defaultIfEmpty(UmamiConfig.EMPTY);
    }

    private record UmamiConfig(String serverUrl, String websiteId, String apiKey,
                               String username, String password) {
        static final UmamiConfig EMPTY = new UmamiConfig("", "", "", "", "");

        boolean configured() {
            return StringUtils.isNotBlank(serverUrl)
                && StringUtils.isNotBlank(websiteId)
                && (StringUtils.isNotBlank(apiKey)
                || (StringUtils.isNotBlank(username) && StringUtils.isNotBlank(password)));
        }
    }
}
