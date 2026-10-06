<script setup lang="ts">
import { onMounted, ref } from 'vue';
import { useAgentStore } from '../stores/agentStore';
import AgentCardComponent from '../components/AgentCard.vue';
import { Search, Plus, Zap, CheckCircle2, AlertTriangle, XCircle, Radio } from 'lucide-vue-next';

const store = useAgentStore();
const showRegisterModal = ref(false);
const newAgentUrl = ref('');

onMounted(() => {
  store.fetchAgents();
  store.fetchHubStats();
  store.initWebSocket();
});

const handleRegister = async () => {
  if (!newAgentUrl.value) return;
  const success = await store.registerAgent(newAgentUrl.value);
  if (success) {
    showRegisterModal.value = false;
    newAgentUrl.value = '';
  }
};
</script>

<template>
  <div class="max-w-7xl mx-auto p-6 space-y-6">
    <!-- Top Header -->
    <div class="flex flex-col sm:flex-row justify-between items-start sm:items-center gap-4">
      <div>
        <div class="flex items-center gap-2 mb-1">
          <span class="inline-flex items-center gap-1.5 px-2.5 py-0.5 rounded-full text-xs font-medium bg-emerald-50 text-emerald-700 border border-emerald-200">
            <Radio class="w-3 h-3 animate-pulse text-emerald-600" />
            Live WebSocket Telemetry
          </span>
        </div>
        <h1 class="text-3xl font-bold text-gray-900 tracking-tight">Agent Registry</h1>
        <p class="text-gray-500 mt-1">Manage, discover, and monitor connected A2A intelligent agents in real-time.</p>
      </div>
      <button 
        @click="showRegisterModal = true"
        class="bg-blue-600 hover:bg-blue-700 text-white px-4 py-2.5 rounded-lg font-medium flex items-center gap-2 transition-colors shadow-sm cursor-pointer"
      >
        <Plus class="w-5 h-5" />
        Register Agent
      </button>
    </div>

    <!-- Telemetry Metrics Bar -->
    <div v-if="store.hubStats" class="grid grid-cols-2 sm:grid-cols-5 gap-3">
      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-4">
        <span class="text-xs font-medium text-gray-500 uppercase tracking-wider">Total Agents</span>
        <p class="text-2xl font-bold text-gray-900 mt-1">{{ store.hubStats.totalAgents }}</p>
      </div>

      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-4">
        <div class="flex items-center justify-between text-emerald-600">
          <span class="text-xs font-medium uppercase tracking-wider text-gray-500">Healthy</span>
          <CheckCircle2 class="w-3.5 h-3.5" />
        </div>
        <p class="text-2xl font-bold text-emerald-600 mt-1">{{ store.hubStats.healthyAgents }}</p>
      </div>

      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-4">
        <div class="flex items-center justify-between text-amber-600">
          <span class="text-xs font-medium uppercase tracking-wider text-gray-500">Degraded</span>
          <AlertTriangle class="w-3.5 h-3.5" />
        </div>
        <p class="text-2xl font-bold text-amber-600 mt-1">{{ store.hubStats.degradedAgents }}</p>
      </div>

      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-4">
        <div class="flex items-center justify-between text-rose-600">
          <span class="text-xs font-medium uppercase tracking-wider text-gray-500">Offline</span>
          <XCircle class="w-3.5 h-3.5" />
        </div>
        <p class="text-2xl font-bold text-rose-600 mt-1">{{ store.hubStats.offlineAgents }}</p>
      </div>

      <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-4">
        <div class="flex items-center justify-between text-blue-600">
          <span class="text-xs font-medium uppercase tracking-wider text-gray-500">Avg Latency</span>
          <Zap class="w-3.5 h-3.5" />
        </div>
        <p class="text-2xl font-bold text-gray-900 mt-1">
          {{ store.hubStats.averageLatencyMs }} <span class="text-xs font-normal text-gray-500">ms</span>
        </p>
      </div>
    </div>

    <!-- Error Banner -->
    <div v-if="store.error" class="bg-red-50 border-l-4 border-red-500 p-4 rounded-r-lg">
      <div class="flex">
        <div class="ml-3">
          <p class="text-sm text-red-700">{{ store.error }}</p>
        </div>
      </div>
    </div>

    <!-- Agent Grid -->
    <div v-if="store.loading && store.agents.length === 0" class="flex justify-center py-20">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
    </div>
    
    <div v-else-if="store.agents.length === 0" class="text-center py-20 border-2 border-dashed border-gray-200 rounded-xl bg-white">
      <Search class="mx-auto h-12 w-12 text-gray-400 mb-4" />
      <h3 class="text-lg font-semibold text-gray-900 mb-1">No agents registered</h3>
      <p class="text-sm text-gray-500">Get started by registering your first A2A agent.</p>
    </div>

    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      <AgentCardComponent 
        v-for="agent in store.agents" 
        :key="agent.id" 
        :agent="agent"
        @unregister="store.unregisterAgent(agent.id)"
        @probe="store.triggerHealthProbe"
      />
    </div>

    <!-- Register Modal -->
    <div
      v-if="showRegisterModal"
      class="fixed inset-0 bg-black/50 backdrop-blur-sm flex items-center justify-center z-50 p-4"
      role="dialog"
      aria-modal="true"
      aria-labelledby="register-modal-title"
      @keydown.esc="showRegisterModal = false"
      @click.self="showRegisterModal = false"
    >
      <div class="bg-white rounded-xl shadow-xl w-full max-w-md overflow-hidden" role="document">
        <div class="px-6 py-4 border-b border-gray-100">
          <h2 id="register-modal-title" class="text-lg font-semibold text-gray-900">Register New Agent</h2>
        </div>
        <div class="p-6">
          <label for="agent-url-input" class="block text-sm font-medium text-gray-700 mb-2">Agent URL</label>
          <input 
            id="agent-url-input"
            v-model="newAgentUrl"
            type="url" 
            placeholder="https://api.example.com/agent"
            class="w-full px-4 py-2 border border-gray-300 rounded-lg focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-shadow text-sm"
            @keyup.enter="handleRegister"
            autofocus
          />
          <p class="text-xs text-gray-500 mt-2">
            The hub will fetch the agent's capabilities from <code class="bg-gray-100 px-1 py-0.5 rounded font-mono">/.well-known/agent-card.json</code>
          </p>
        </div>
        <div class="px-6 py-4 bg-gray-50 flex justify-end gap-3">
          <button 
            type="button"
            @click="showRegisterModal = false"
            class="px-4 py-2 text-gray-700 hover:bg-gray-200 rounded-lg font-medium text-sm transition-colors cursor-pointer"
          >
            Cancel
          </button>
          <button 
            type="button"
            @click="handleRegister"
            :disabled="store.loading || !newAgentUrl"
            class="bg-blue-600 hover:bg-blue-700 disabled:opacity-50 text-white px-4 py-2 rounded-lg font-medium text-sm transition-colors flex items-center gap-2 cursor-pointer shadow-sm"
          >
            <span v-if="store.loading" class="animate-spin rounded-full h-4 w-4 border-b-2 border-white" aria-hidden="true"></span>
            Register
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
