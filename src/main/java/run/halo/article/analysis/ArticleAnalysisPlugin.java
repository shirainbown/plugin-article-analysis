package run.halo.article.analysis;

import org.springframework.stereotype.Component;
import run.halo.app.plugin.BasePlugin;
import run.halo.app.plugin.PluginContext;

/**
 * 文章数据分析插件：console 端提供文章数据看板页面。
 * v1 纯前端实现（数据全部来自 Halo console 核心 API），无自定义后端端点。
 *
 * @author shirainbown
 */
@Component
public class ArticleAnalysisPlugin extends BasePlugin {

    public ArticleAnalysisPlugin(PluginContext pluginContext) {
        super(pluginContext);
    }
}
