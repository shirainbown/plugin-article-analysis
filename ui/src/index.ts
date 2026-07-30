import { IconDashboard } from '@halo-dev/components';
import { definePlugin } from '@halo-dev/ui-shared';
import { markRaw } from 'vue';
import './styles/index.css';

export default definePlugin({
  components: {},
  routes: [
    {
      parentName: 'Root',
      route: {
        path: '/article-analysis',
        name: 'ArticleAnalysis',
        component: () => import('@/views/ArticleDataBoard.vue'),
        meta: {
          title: '文章数据',
          permissions: ['system:posts:view'],
          menu: {
            name: '文章数据',
            group: 'content',
            icon: markRaw(IconDashboard),
            priority: 52,
          },
        },
      },
    },
  ],
  extensionPoints: {},
});
