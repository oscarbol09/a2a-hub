<script setup lang="ts">
import { useRoute } from 'vue-router';
import { Network, Database, Compass, Workflow } from 'lucide-vue-next';

const route = useRoute();
</script>

<template>
  <div class="min-h-screen bg-gray-50 font-sans text-gray-900">
    <nav class="bg-white border-b border-gray-200 sticky top-0 z-40">
      <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8">
        <div class="flex justify-between h-16">
          <div class="flex items-center gap-3">
            <router-link to="/" class="flex items-center gap-2.5">
              <div class="bg-blue-600 text-white p-1.5 rounded-lg shadow-sm">
                <Network class="w-5 h-5" />
              </div>
              <span class="font-bold text-xl tracking-tight text-gray-900">A2A Hub</span>
            </router-link>
            <span class="hidden sm:inline-block text-xs font-mono bg-blue-50 text-blue-700 px-2 py-0.5 rounded border border-blue-200">
              v0.2 Discovery
            </span>
          </div>
          <div class="flex items-center space-x-1 sm:space-x-3">
            <router-link
              to="/"
              :class="[
                'flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors',
                route.name === 'registry'
                  ? 'bg-gray-100 text-gray-900 font-semibold'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'
              ]"
            >
              <Database class="w-4 h-4 text-gray-500" />
              <span>Registry</span>
            </router-link>

            <router-link
              to="/discover"
              :class="[
                'flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors',
                route.name === 'discover'
                  ? 'bg-blue-50 text-blue-700 font-semibold border border-blue-100'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'
              ]"
            >
              <Compass class="w-4 h-4 text-blue-600" />
              <span>Discovery</span>
            </router-link>

            <router-link
              to="/orchestrate"
              :class="[
                'flex items-center gap-1.5 px-3 py-2 rounded-lg text-sm font-medium transition-colors',
                route.name === 'orchestrate'
                  ? 'bg-blue-50 text-blue-700 font-semibold border border-blue-100'
                  : 'text-gray-600 hover:text-gray-900 hover:bg-gray-50'
              ]"
            >
              <Workflow class="w-4 h-4" :class="route.name === 'orchestrate' ? 'text-blue-600' : 'text-gray-500'" />
              <span>Orchestrate</span>
            </router-link>
          </div>
        </div>
      </div>
    </nav>

    <main>
      <router-view />
    </main>
  </div>
</template>
