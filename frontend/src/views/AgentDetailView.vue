<script setup lang="ts">
import { ref, onMounted, computed } from 'vue';
import { useRoute, useRouter } from 'vue-router';
import { healthApi, type AgentHealthResponse } from '../services/api';
import { useAgentStore } from '../stores/agentStore';
import {
  ArrowLeft,
  Bot,
  RefreshCw,
  Clock,
  Zap,
  CheckCircle2,
  ExternalLink
} from 'lucide-vue-next';

const route = useRoute();
const router = useRouter();
const store = useAgentStore();

const agentId = route.params.id as string;
const healthData = ref<AgentHealthResponse | null>(null);
const loading = ref(true);
const probing = ref(false);
const error = ref<string | null>(null);

const fetchHealthHistory = async () => {
  loading.value = true;
  error.value = null;
  try {
    const res = await healthApi.getAgentHealth(agentId, 30);
    healthData.value = res.data;
  } catch (err: any) {
    error.value = err.response?.data?.message || 'Failed to fetch agent health data';
  } finally {
    loading.value = false;
  }
};

const triggerProbe = async () => {
  probing.value = true;
  try {
    await store.triggerHealthProbe(agentId);
    await fetchHealthHistory();
  } catch (err: any) {
    error.value = err.response?.data?.message || 'Manual probe failed';
  } finally {
    probing.value = false;
  }
};

const agent = computed(() => store.agents.find(a => a.id === agentId));

const avgLatency = computed(() => {
  if (!healthData.value || healthData.value.history.length === 0) return 0;
  const valid = healthData.value.history.filter(h => h.latencyMs !== null);
  if (valid.length === 0) return 0;
  const sum = valid.reduce((acc, curr) => acc + (curr.latencyMs || 0), 0);
  return Math.round(sum / valid.length);
});

const maxLatency = computed(() => {
  if (!healthData.value || healthData.value.history.length === 0) return 100;
  const max = Math.max(...healthData.value.history.map(h => h.latencyMs || 0));
  return max > 0 ? max : 100;
});

const uptimePercent = computed(() => {
  if (!healthData.value || healthData.value.history.length === 0) return 100;
  const healthy = healthData.value.history.filter(h => h.status === 'HEALTHY' || h.status === 'DEGRADED').length;
  return Math.round((healthy / healthData.value.history.length) * 100);
});

const statusBadge = computed(() => {
  const status = healthData.value?.currentStatus || agent.value?.status || 'UNKNOWN';
  switch (status) {
    case 'HEALTHY':
      return { text: 'HEALTHY', bg: 'bg-emerald-50 text-emerald-700 border-emerald-200', dot: 'bg-emerald-500', ping: 'bg-emerald-400' };
    case 'DEGRADED':
      return { text: 'DEGRADED', bg: 'bg-amber-50 text-amber-700 border-amber-200', dot: 'bg-amber-500', ping: 'bg-amber-400' };
    case 'OFFLINE':
      return { text: 'OFFLINE', bg: 'bg-rose-50 text-rose-700 border-rose-200', dot: 'bg-rose-500', ping: 'bg-rose-400' };
    default:
      return { text: 'UNKNOWN', bg: 'bg-gray-50 text-gray-700 border-gray-200', dot: 'bg-gray-400', ping: 'bg-gray-300' };
  }
});

onMounted(async () => {
  await fetchHealthHistory();
  if (!agent.value) {
    await store.fetchAgentById(agentId);
  }
});

defineExpose({
  healthData,
  loading,
  probing,
  error,
  agent,
  avgLatency,
  maxLatency,
  uptimePercent,
  statusBadge,
  fetchHealthHistory,
  triggerProbe,
});
</script>

<template>
  <div class="max-w-6xl mx-auto p-6 space-y-6">
    <!-- Back Navigation -->
    <div>
      <button
        @click="router.push('/')"
        class="inline-flex items-center gap-1.5 text-sm font-medium text-gray-500 hover:text-gray-900 transition-colors"
      >
        <ArrowLeft class="w-4 h-4" />
        Back to Registry
      </button>
    </div>

    <!-- Error Banner -->
    <div v-if="error" class="bg-red-50 border-l-4 border-red-500 p-4 rounded-r-lg">
      <p class="text-sm text-red-700">{{ error }}</p>
    </div>

    <!-- Agent Header Card -->
    <div v-if="healthData || agent" class="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
      <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
        <div class="flex items-center gap-4">
          <div class="w-12 h-12 rounded-xl bg-blue-600 text-white flex items-center justify-center shrink-0 shadow-sm">
            <Bot class="w-7 h-7" />
          </div>
          <div>
            <div class="flex items-center gap-3">
              <h1 class="text-2xl font-bold text-gray-900">
                {{ healthData?.agentName || agent?.name }}
              </h1>
              <div
                class="inline-flex items-center gap-1.5 px-3 py-1 rounded-full text-xs font-semibold border"
                :class="statusBadge.bg"
              >
                <span class="relative flex h-2 w-2">
                  <span
                    v-if="statusBadge.text === 'HEALTHY' || statusBadge.text === 'DEGRADED'"
                    class="animate-ping absolute inline-flex h-full w-full rounded-full opacity-75"
                    :class="statusBadge.ping"
                  ></span>
                  <span class="relative inline-flex rounded-full h-2 w-2" :class="statusBadge.dot"></span>
                </span>
                <span>{{ statusBadge.text }}</span>
              </div>
            </div>
            <div class="flex items-center gap-3 mt-1.5 text-xs text-gray-500">
              <a
                :href="agent?.url"
                target="_blank"
                rel="noreferrer"
                class="font-mono text-blue-600 hover:underline inline-flex items-center gap-1"
              >
                {{ agent?.url }}
                <ExternalLink class="w-3 h-3" />
              </a>
              <span>•</span>
              <span>Version: v{{ agent?.version || '1.0.0' }}</span>
              <span>•</span>
              <span>Auth: {{ agent?.authType || 'NONE' }}</span>
            </div>
          </div>
        </div>

        <button
          @click="triggerProbe"
          :disabled="probing"
          class="inline-flex items-center gap-2 px-4 py-2 bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white text-sm font-medium rounded-lg transition-colors shadow-sm cursor-pointer"
        >
          <RefreshCw class="w-4 h-4" :class="{ 'animate-spin': probing }" />
          <span>{{ probing ? 'Probing...' : 'Probe Health Now' }}</span>
        </button>
      </div>
    </div>

    <!-- Quick Telemetry Stats -->
    <div class="grid grid-cols-1 sm:grid-cols-3 gap-4">
      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
        <div class="flex items-center justify-between text-gray-500 mb-2">
          <span class="text-xs font-medium uppercase tracking-wider">Availability (30 checks)</span>
          <CheckCircle2 class="w-4 h-4 text-emerald-500" />
        </div>
        <p class="text-2xl font-bold text-gray-900">{{ uptimePercent }}%</p>
        <p class="text-xs text-gray-400 mt-1">Probe success rate</p>
      </div>

      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
        <div class="flex items-center justify-between text-gray-500 mb-2">
          <span class="text-xs font-medium uppercase tracking-wider">Average Latency</span>
          <Zap class="w-4 h-4 text-amber-500" />
        </div>
        <p class="text-2xl font-bold text-gray-900">{{ avgLatency }} <span class="text-sm font-normal text-gray-500">ms</span></p>
        <p class="text-xs text-gray-400 mt-1">Round-trip response time</p>
      </div>

      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-5">
        <div class="flex items-center justify-between text-gray-500 mb-2">
          <span class="text-xs font-medium uppercase tracking-wider">Last Health Probe</span>
          <Clock class="w-4 h-4 text-blue-500" />
        </div>
        <p class="text-base font-semibold text-gray-900 truncate">
          {{ healthData?.lastSeenAt ? new Date(healthData.lastSeenAt).toLocaleTimeString() : 'Never' }}
        </p>
        <p class="text-xs text-gray-400 mt-1">
          {{ healthData?.lastSeenAt ? new Date(healthData.lastSeenAt).toLocaleDateString() : 'Awaiting first probe' }}
        </p>
      </div>
    </div>

    <!-- Latency Timeline Visualization -->
    <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-6">
      <div class="flex items-center justify-between mb-4">
        <div>
          <h2 class="text-base font-semibold text-gray-900">Probe Latency History</h2>
          <p class="text-xs text-gray-500">Recent check response times (chronological left-to-right)</p>
        </div>
        <span class="text-xs font-mono text-gray-400">Max: {{ maxLatency }}ms</span>
      </div>

      <!-- Latency Spark Bars -->
      <div v-if="healthData?.history && healthData.history.length > 0" class="flex items-end gap-1.5 h-24 pt-4 border-b border-gray-100">
        <div
          v-for="item in [...healthData.history].reverse()"
          :key="item.id"
          class="flex-1 flex flex-col items-center group relative h-full justify-end"
        >
          <!-- Tooltip -->
          <div class="absolute bottom-full mb-2 hidden group-hover:flex flex-col items-center z-20 pointer-events-none">
            <div class="bg-gray-900 text-white text-[10px] rounded px-2 py-1 shadow-lg whitespace-nowrap">
              <p class="font-bold">{{ item.status }} — {{ item.latencyMs ? item.latencyMs + 'ms' : 'No response' }}</p>
              <p class="text-gray-400">{{ new Date(item.checkedAt).toLocaleTimeString() }}</p>
              <p v-if="item.errorMessage" class="text-rose-400 max-w-xs truncate">{{ item.errorMessage }}</p>
            </div>
            <div class="w-1.5 h-1.5 bg-gray-900 rotate-45 -mt-0.5"></div>
          </div>

          <!-- Bar -->
          <div
            class="w-full rounded-t transition-all"
            :class="[
              item.status === 'HEALTHY'
                ? 'bg-emerald-400 group-hover:bg-emerald-500'
                : item.status === 'DEGRADED'
                ? 'bg-amber-400 group-hover:bg-amber-500'
                : 'bg-rose-400 group-hover:bg-rose-500'
            ]"
            :style="{
              height: Math.max(item.latencyMs ? (item.latencyMs / maxLatency) * 100 : 8, 8) + '%'
            }"
          ></div>
        </div>
      </div>
      <div v-else class="text-center py-8 text-sm text-gray-400">
        No probe metrics recorded yet.
      </div>
    </div>

    <!-- Health Logs Table -->
    <div class="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden">
      <div class="px-6 py-4 border-b border-gray-100 flex justify-between items-center">
        <h2 class="text-base font-semibold text-gray-900">Health Probe Logs</h2>
        <span class="text-xs text-gray-500">Auto-purged after 48h</span>
      </div>

      <div class="overflow-x-auto">
        <table class="w-full text-left text-sm">
          <thead class="bg-gray-50 text-gray-500 text-xs uppercase tracking-wider">
            <tr>
              <th class="px-6 py-3">Timestamp</th>
              <th class="px-6 py-3">Status</th>
              <th class="px-6 py-3">Latency</th>
              <th class="px-6 py-3">Details / Errors</th>
            </tr>
          </thead>
          <tbody class="divide-y divide-gray-100">
            <tr v-if="loading && (!healthData || healthData.history.length === 0)">
              <td colspan="4" class="px-6 py-8 text-center text-gray-400">
                <div class="animate-spin inline-block w-5 h-5 border-2 border-blue-600 border-t-transparent rounded-full"></div>
              </td>
            </tr>
            <tr v-else-if="!healthData || healthData.history.length === 0">
              <td colspan="4" class="px-6 py-8 text-center text-gray-400 text-sm">
                No health check logs found for this agent.
              </td>
            </tr>
            <tr
              v-for="item in healthData?.history"
              :key="item.id"
              class="hover:bg-gray-50/50 transition-colors"
            >
              <td class="px-6 py-3.5 text-xs text-gray-600 font-mono">
                {{ new Date(item.checkedAt).toLocaleString() }}
              </td>
              <td class="px-6 py-3.5">
                <span
                  class="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium"
                  :class="[
                    item.status === 'HEALTHY' ? 'bg-emerald-50 text-emerald-700' :
                    item.status === 'DEGRADED' ? 'bg-amber-50 text-amber-700' :
                    'bg-rose-50 text-rose-700'
                  ]"
                >
                  <span
                    class="w-1.5 h-1.5 rounded-full"
                    :class="[
                      item.status === 'HEALTHY' ? 'bg-emerald-500' :
                      item.status === 'DEGRADED' ? 'bg-amber-500' :
                      'bg-rose-500'
                    ]"
                  ></span>
                  {{ item.status }}
                </span>
              </td>
              <td class="px-6 py-3.5 text-xs font-mono text-gray-700">
                {{ item.latencyMs !== null ? item.latencyMs + ' ms' : '—' }}
              </td>
              <td class="px-6 py-3.5 text-xs text-gray-500 max-w-md truncate">
                {{ item.errorMessage || 'HTTP 200 OK — Agent Card valid' }}
              </td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </div>
</template>
