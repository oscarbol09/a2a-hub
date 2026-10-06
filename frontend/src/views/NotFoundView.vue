<script setup lang="ts">
import { useRouter } from 'vue-router';
import { Compass, ArrowLeft } from 'lucide-vue-next';

const router = useRouter();
</script>

<template>
  <div class="min-h-[70vh] flex items-center justify-center px-4">
    <div class="text-center max-w-md mx-auto">
      <div class="inline-flex items-center justify-center w-16 h-16 rounded-2xl bg-indigo-50 dark:bg-indigo-950/50 text-indigo-600 dark:text-indigo-400 mb-6 ring-8 ring-indigo-50/50 dark:ring-indigo-950/20">
        <Compass class="w-8 h-8" />
      </div>
      <h1 class="text-3xl font-bold text-gray-900 dark:text-gray-100 mb-2">404 — Page Not Found</h1>
      <p class="text-gray-600 dark:text-gray-400 mb-8 text-sm leading-relaxed">
        The agent endpoint or view you are looking for does not exist or has been unregistered from the hub.
      </p>
      <button
        @click="router.push('/')"
        class="inline-flex items-center gap-2 px-5 py-2.5 rounded-xl bg-indigo-600 hover:bg-indigo-700 text-white text-sm font-medium transition-colors shadow-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 focus:ring-offset-2 dark:focus:ring-offset-gray-900"
      >
        <ArrowLeft class="w-4 h-4" />
        Back to Registry
      </button>
    </div>
  </div>
</template>
