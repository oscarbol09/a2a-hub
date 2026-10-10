import { createRouter, createWebHistory } from 'vue-router';

const router = createRouter({
  history: createWebHistory(import.meta.env.BASE_URL),
  scrollBehavior(_to, _from, savedPosition) {
    if (savedPosition) {
      return savedPosition;
    }
    return { top: 0 };
  },
  routes: [
    {
      path: '/',
      name: 'registry',
      component: () => import('../views/AgentRegistryView.vue')
    },
    {
      path: '/discover',
      name: 'discover',
      component: () => import('../views/DiscoverView.vue')
    },
    {
      path: '/agents/:id',
      name: 'agent-detail',
      component: () => import('../views/AgentDetailView.vue')
    },
    {
      path: '/orchestrate',
      name: 'orchestrate',
      component: () => import('../views/OrchestrateView.vue')
    },
    {
      path: '/:pathMatch(.*)*',
      name: 'not-found',
      component: () => import('../views/NotFoundView.vue')
    }
  ]
});

export default router;
