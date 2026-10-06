<script setup lang="ts">
import { computed, ref } from 'vue';
import { useRouter } from 'vue-router';
import type { Agent } from '../services/api';
import { Bot, Trash2, Activity, ShieldCheck, Cpu, RefreshCw, ArrowUpRight } from 'lucide-vue-next';

const props = defineProps<{
  agent: Agent
}>();

const emit = defineEmits<{
  (e: 'unregister'): void;
  (e: 'probe', id: string): void;
}>();

const router = useRouter();
const probing = ref(false);

const statusColor = computed(() => {
  switch (props.agent.status) {
    case 'HEALTHY': return 'bg-emerald-500';
    case 'DEGRADED': return 'bg-amber-500';
    case 'OFFLINE': return 'bg-rose-500';
    default: return 'bg-gray-400';
  }
});

const statusPingColor = computed(() => {
  switch (props.agent.status) {
    case 'HEALTHY': return 'bg-emerald-400';
    case 'DEGRADED': return 'bg-amber-400';
    case 'OFFLINE': return 'bg-rose-400';
    default: return 'bg-gray-300';
  }
});

const statusTextClass = computed(() => {
  switch (props.agent.status) {
    case 'HEALTHY': return 'text-emerald-700';
    case 'DEGRADED': return 'text-amber-700';
    case 'OFFLINE': return 'text-rose-700';
    default: return 'text-gray-700';
  }
});

const statusBgClass = computed(() => {
  switch (props.agent.status) {
    case 'HEALTHY': return 'bg-emerald-50 border-emerald-200';
    case 'DEGRADED': return 'bg-amber-50 border-amber-200';
    case 'OFFLINE': return 'bg-rose-50 border-rose-200';
    default: return 'bg-gray-50 border-gray-200';
  }
});

const skills = computed(() => props.agent.agentCard?.skills ?? []);
const totalSkillsCount = computed(() => skills.value.length);
const displayedSkills = computed(() => skills.value.slice(0, 3));
const extraSkillsCount = computed(() => Math.max(0, skills.value.length - 3));

const formattedTimestamp = computed(() => {
  if (props.agent.lastSeenAt) {
    return `Seen ${new Date(props.agent.lastSeenAt).toLocaleTimeString()}`;
  }
  return `Registered ${new Date(props.agent.registeredAt).toLocaleDateString()}`;
});

const handleQuickProbe = (e: Event) => {
  e.stopPropagation();
  probing.value = true;
  emit('probe', props.agent.id);
  setTimeout(() => {
    probing.value = false;
  }, 1200);
};

const goToDetail = () => {
  router.push(`/agents/${props.agent.id}`);
};

defineExpose({
  probing,
  statusColor,
  statusPingColor,
  statusTextClass,
  statusBgClass,
  skills,
  totalSkillsCount,
  displayedSkills,
  extraSkillsCount,
  formattedTimestamp,
  handleQuickProbe,
  goToDetail,
});
</script>

<template>
  <div
    @click="goToDetail"
    @keydown.enter="goToDetail"
    @keydown.space.prevent="goToDetail"
    tabindex="0"
    role="button"
    :aria-label="'View details for ' + agent.name"
    class="bg-white rounded-xl shadow-sm border border-gray-200 overflow-hidden hover:shadow-md hover:border-blue-300 focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent transition-all group flex flex-col cursor-pointer"
  >
    <!-- Header -->
    <div class="p-5 border-b border-gray-100 flex justify-between items-start">
      <div class="flex gap-3">
        <div class="w-10 h-10 rounded-lg bg-blue-50 text-blue-600 flex items-center justify-center shrink-0 group-hover:bg-blue-600 group-hover:text-white transition-colors" aria-hidden="true">
          <Bot class="w-6 h-6" />
        </div>
        <div>
          <div class="flex items-center gap-1.5">
            <h3 class="font-semibold text-gray-900 leading-tight group-hover:text-blue-600 transition-colors">
              {{ agent.name }}
            </h3>
            <ArrowUpRight class="w-3.5 h-3.5 text-gray-400 opacity-0 group-hover:opacity-100 transition-opacity" aria-hidden="true" />
          </div>
          <p class="text-xs text-gray-500 mt-1 font-mono truncate max-w-[190px]" :title="agent.url">
            {{ agent.url }}
          </p>
        </div>
      </div>

      <!-- Real-time Status Badge with Pulse -->
      <div class="flex flex-col items-end gap-1">
        <div
          class="inline-flex items-center gap-1.5 px-2.5 py-1 rounded-full text-xs font-medium border"
          :class="[statusBgClass, statusTextClass]"
          :aria-label="'Agent status: ' + agent.status"
        >
          <span class="relative flex h-2 w-2" aria-hidden="true">
            <span
              v-if="agent.status === 'HEALTHY' || agent.status === 'DEGRADED'"
              class="animate-ping absolute inline-flex h-full w-full rounded-full opacity-75"
              :class="statusPingColor"
            ></span>
            <span class="relative inline-flex rounded-full h-2 w-2" :class="statusColor"></span>
          </span>
          <span>{{ agent.status }}</span>
        </div>
        <span v-if="agent.latencyMs !== undefined" class="text-[10px] text-gray-400 font-mono" :aria-label="'Latency ' + agent.latencyMs + ' milliseconds'">
          {{ agent.latencyMs }}ms
        </span>
      </div>
    </div>

    <!-- Body -->
    <div class="p-5 flex-grow">
      <p class="text-sm text-gray-600 line-clamp-2 mb-4 h-10">
        {{ agent.description || 'No description provided.' }}
      </p>

      <div class="space-y-3">
        <!-- Version & Auth -->
        <div class="flex gap-4 text-xs text-gray-500 font-medium">
          <div class="flex items-center gap-1.5">
            <Cpu class="w-3.5 h-3.5 text-gray-400" />
            <span>v{{ agent.version || '1.0' }}</span>
          </div>
          <div class="flex items-center gap-1.5">
            <ShieldCheck class="w-3.5 h-3.5 text-gray-400" />
            <span>{{ agent.authType }}</span>
          </div>
        </div>

        <!-- Skills -->
        <div>
          <p class="text-[11px] font-semibold text-gray-400 uppercase tracking-wider mb-2">
            Skills ({{ totalSkillsCount }})
          </p>
          <div class="flex flex-wrap gap-1.5">
            <span
              v-for="skill in displayedSkills"
              :key="skill.id"
              class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-gray-100 text-gray-700"
              :title="skill.description"
            >
              {{ skill.name }}
            </span>
            <span
              v-if="extraSkillsCount > 0"
              class="inline-flex items-center px-2 py-0.5 rounded text-xs font-medium bg-gray-100 text-gray-500"
            >
              +{{ extraSkillsCount }} more
            </span>
          </div>
        </div>
      </div>
    </div>

    <!-- Footer -->
    <div class="px-5 py-3 bg-gray-50 border-t border-gray-100 flex justify-between items-center text-xs text-gray-500">
      <div class="flex items-center gap-1.5">
        <Activity class="w-3.5 h-3.5 text-gray-400" />
        <span>{{ formattedTimestamp }}</span>
      </div>

      <div class="flex items-center gap-2" @click.stop @keydown.stop>
        <button
          @click="handleQuickProbe"
          :disabled="probing"
          class="text-gray-400 hover:text-blue-600 transition-colors p-1.5 rounded-lg hover:bg-gray-100 focus:outline-none focus:ring-2 focus:ring-blue-500 disabled:opacity-50"
          :title="'Probe health for ' + agent.name"
          :aria-label="'Probe health for ' + agent.name"
        >
          <RefreshCw class="w-3.5 h-3.5" :class="{ 'animate-spin text-blue-600': probing }" aria-hidden="true" />
        </button>
        <button
          @click="$emit('unregister')"
          class="text-gray-400 hover:text-rose-600 transition-colors p-1.5 rounded-lg hover:bg-rose-50 focus:outline-none focus:ring-2 focus:ring-rose-500"
          :title="'Unregister ' + agent.name"
          :aria-label="'Unregister ' + agent.name"
        >
          <Trash2 class="w-3.5 h-3.5" aria-hidden="true" />
        </button>
      </div>
    </div>
  </div>
</template>
