<script setup lang="ts">
import { computed } from 'vue';
import type { TaskDto } from '../services/api';
import { CheckCircle2, XCircle, Clock, Loader2 } from 'lucide-vue-next';

const props = defineProps<{
  task: TaskDto | null;
}>();

const stateConfig = computed(() => {
  if (!props.task) return null;
  
  switch (props.task.state) {
    case 'SUBMITTED':
    case 'PENDING':
    case 'RUNNING':
      return {
        icon: Loader2,
        iconClass: 'animate-spin text-blue-500',
        bgClass: 'bg-blue-50 border-blue-200',
        textClass: 'text-blue-700'
      };
    case 'COMPLETED':
    case 'SUCCESS':
      return {
        icon: CheckCircle2,
        iconClass: 'text-emerald-500',
        bgClass: 'bg-emerald-50 border-emerald-200',
        textClass: 'text-emerald-700'
      };
    case 'FAILED':
    case 'ERROR':
      return {
        icon: XCircle,
        iconClass: 'text-red-500',
        bgClass: 'bg-red-50 border-red-200',
        textClass: 'text-red-700'
      };
    default:
      return {
        icon: Clock,
        iconClass: 'text-gray-500',
        bgClass: 'bg-gray-50 border-gray-200',
        textClass: 'text-gray-700'
      };
  }
});
</script>

<template>
  <div v-if="task" class="w-full bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
    <div class="px-6 py-5 border-b border-gray-200 flex items-center justify-between">
      <div>
        <h3 class="text-lg font-medium text-gray-900 flex items-center gap-2">
          Task Execution
        </h3>
        <p class="text-sm text-gray-500 mt-1">ID: {{ task.id }}</p>
      </div>
      <div v-if="stateConfig" :class="['px-3 py-1.5 rounded-full border flex items-center gap-2 text-sm font-medium', stateConfig.bgClass, stateConfig.textClass]">
        <component :is="stateConfig.icon" :class="['w-4 h-4', stateConfig.iconClass]" />
        {{ task.state }}
      </div>
    </div>
    
    <div class="p-6 space-y-6">
      <div class="space-y-3">
        <h4 class="text-sm font-medium text-gray-700 uppercase tracking-wider">Payload</h4>
        <div class="bg-gray-50 rounded-lg p-4 overflow-x-auto border border-gray-200">
          <pre class="text-sm text-gray-800 font-mono">{{ JSON.stringify(task.request, null, 2) }}</pre>
        </div>
      </div>

      <div class="space-y-3" v-if="task.response">
        <h4 class="text-sm font-medium text-gray-700 uppercase tracking-wider">Response</h4>
        <div class="bg-emerald-50 rounded-lg p-4 overflow-x-auto border border-emerald-200">
          <pre class="text-sm text-emerald-900 font-mono">{{ JSON.stringify(task.response, null, 2) }}</pre>
        </div>
      </div>

      <div class="space-y-3" v-if="task.errorDetail">
        <h4 class="text-sm font-medium text-gray-700 uppercase tracking-wider">Error Details</h4>
        <div class="bg-red-50 rounded-lg p-4 overflow-x-auto border border-red-200">
          <pre class="text-sm text-red-900 font-mono">{{ task.errorDetail }}</pre>
        </div>
      </div>
    </div>
  </div>
</template>
