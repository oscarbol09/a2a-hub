<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { useTaskStore } from '../stores/taskStore';
import { agentsApi, type Agent, orchestrationApi } from '../services/api';
import TaskTimeline from '../components/TaskTimeline.vue';
import { Send, AlertCircle, Sparkles } from 'lucide-vue-next';

const taskStore = useTaskStore();
const agents = ref<Agent[]>([]);
const selectedAgentId = ref('');
const payloadInput = ref('{\n  "query": "Hello Agent"\n}');
const isLoadingAgents = ref(false);
const error = ref<string | null>(null);

const aiTaskDescription = ref('');
const isSuggesting = ref(false);
const aiSuggestionReasoning = ref<string | null>(null);
const aiSuggestionError = ref<string | null>(null);

onMounted(async () => {
  isLoadingAgents.value = true;
  try {
    const res = await agentsApi.getAll();
    agents.value = res.data.filter(a => a.status === 'HEALTHY' || a.status === 'DEGRADED');
    if (agents.value.length > 0) {
      selectedAgentId.value = agents.value[0].id;
    }
  } catch (err: any) {
    error.value = 'Failed to load active agents. ' + (err.message || '');
  } finally {
    isLoadingAgents.value = false;
  }
});

const submitTask = async () => {
  if (!selectedAgentId.value) {
    error.value = 'Please select an agent.';
    return;
  }
  
  let payloadStr = payloadInput.value;
  let payloadObj: Record<string, any>;
  try {
    payloadObj = JSON.parse(payloadStr);
  } catch (err) {
    error.value = 'Invalid JSON in payload.';
    return;
  }

  error.value = null;
  taskStore.clearTask();
  
  try {
    await taskStore.submitTask(selectedAgentId.value, payloadObj);
  } catch (err: any) {
    error.value = err.message || 'Failed to submit task.';
  }
};

const suggestAgent = async () => {
  if (!aiTaskDescription.value) return;
  isSuggesting.value = true;
  aiSuggestionError.value = null;
  aiSuggestionReasoning.value = null;
  try {
    const res = await orchestrationApi.suggest({ taskDescription: aiTaskDescription.value });
    selectedAgentId.value = res.data.agentId;
    aiSuggestionReasoning.value = res.data.reasoning;
  } catch (err: any) {
    aiSuggestionError.value = err.response?.data?.message || err.message || 'Failed to get AI suggestion';
  } finally {
    isSuggesting.value = false;
  }
};
</script>

<template>
  <div class="max-w-7xl mx-auto px-4 sm:px-6 lg:px-8 py-8">
    <div class="mb-8">
      <h1 class="text-2xl font-bold text-gray-900">Task Orchestration</h1>
      <p class="mt-1 text-sm text-gray-500">Delegate tasks asynchronously to active agents in the hub.</p>
    </div>

    <div class="grid grid-cols-1 lg:grid-cols-3 gap-8">
      <div class="lg:col-span-1 space-y-6">
        
        <div class="bg-gradient-to-r from-indigo-50 to-purple-50 p-6 rounded-xl shadow-sm border border-indigo-100">
          <div class="flex items-center gap-2 mb-4 text-indigo-900">
            <Sparkles class="w-5 h-5 text-indigo-600" />
            <h2 class="text-lg font-semibold">AI Agent Selector</h2>
          </div>
          <div class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-indigo-900 mb-1">Describe your task to the AI</label>
              <textarea 
                v-model="aiTaskDescription" 
                rows="3" 
                class="w-full bg-white border border-indigo-200 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-indigo-500 placeholder-indigo-300"
                placeholder="E.g., I need someone to analyze logs and find the error..."
              ></textarea>
            </div>

            <div v-if="aiSuggestionError" class="flex items-start gap-2 p-3 bg-red-50 text-red-700 rounded-lg text-sm border border-red-100">
              <AlertCircle class="w-4 h-4 mt-0.5 shrink-0" />
              <span>{{ aiSuggestionError }}</span>
            </div>

            <div v-if="aiSuggestionReasoning" class="p-4 bg-indigo-100 text-indigo-900 rounded-lg text-sm border border-indigo-200 shadow-inner">
              <div class="font-medium mb-1">AI Reasoning:</div>
              <p>{{ aiSuggestionReasoning }}</p>
            </div>

            <button 
              @click="suggestAgent"
              class="w-full flex items-center justify-center gap-2 bg-indigo-600 hover:bg-indigo-700 text-white font-medium px-4 py-2 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              :disabled="isSuggesting || !aiTaskDescription"
            >
              <Sparkles v-if="!isSuggesting" class="w-4 h-4" />
              <svg v-if="isSuggesting" class="animate-spin -ml-1 mr-3 h-5 w-5 text-white" xmlns="http://www.w3.org/2000/svg" fill="none" viewBox="0 0 24 24">
                <circle class="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" stroke-width="4"></circle>
                <path class="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4zm2 5.291A7.962 7.962 0 014 12H0c0 3.042 1.135 5.824 3 7.938l3-2.647z"></path>
              </svg>
              <span>{{ isSuggesting ? 'Thinking...' : 'Get Suggestion' }}</span>
            </button>
          </div>
        </div>

        <div class="bg-white p-6 rounded-xl shadow-sm border border-gray-200">
          <form @submit.prevent="submitTask" class="space-y-4">
            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Target Agent</label>
              <select 
                v-model="selectedAgentId" 
                class="w-full bg-white border border-gray-300 rounded-lg px-3 py-2 text-sm focus:outline-none focus:ring-2 focus:ring-blue-500"
                :disabled="isLoadingAgents"
              >
                <option value="" disabled>Select an agent</option>
                <option v-for="agent in agents" :key="agent.id" :value="agent.id">
                  {{ agent.name }} ({{ agent.status }})
                </option>
              </select>
            </div>

            <div>
              <label class="block text-sm font-medium text-gray-700 mb-1">Task Payload (JSON)</label>
              <textarea 
                v-model="payloadInput" 
                rows="6" 
                class="w-full bg-gray-50 border border-gray-300 rounded-lg px-3 py-2 text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500"
              ></textarea>
            </div>

            <div v-if="error || taskStore.error" class="flex items-start gap-2 p-3 bg-red-50 text-red-700 rounded-lg text-sm border border-red-100">
              <AlertCircle class="w-4 h-4 mt-0.5 shrink-0" />
              <span>{{ error || taskStore.error }}</span>
            </div>

            <button 
              type="submit" 
              class="w-full flex items-center justify-center gap-2 bg-blue-600 hover:bg-blue-700 text-white font-medium px-4 py-2 rounded-lg transition-colors disabled:opacity-50 disabled:cursor-not-allowed"
              :disabled="taskStore.isLoading || isLoadingAgents || !selectedAgentId"
            >
              <Send class="w-4 h-4" />
              <span>Submit Task</span>
            </button>
          </form>
        </div>
      </div>

      <div class="lg:col-span-2">
        <div v-if="taskStore.currentTask">
          <TaskTimeline :task="taskStore.currentTask" />
        </div>
        <div v-else class="h-full flex items-center justify-center border-2 border-dashed border-gray-200 rounded-xl bg-gray-50 p-8 text-center">
          <div class="text-gray-500">
            <Send class="w-8 h-8 mx-auto mb-3 opacity-50" />
            <p class="text-sm font-medium text-gray-900">No active task</p>
            <p class="text-sm mt-1">Submit a task to see its execution timeline.</p>
          </div>
        </div>
      </div>
    </div>
  </div>
</template>
