<script setup lang="ts">
import { ref, onMounted } from 'vue';
import { discoveryApi, type Agent, type SkillSummary, type TagSummary } from '../services/api';
import AgentCardComponent from '../components/AgentCard.vue';
import SkillBadge from '../components/SkillBadge.vue';
import { Search, SlidersHorizontal, X, Compass, Sparkles } from 'lucide-vue-next';

const agents = ref<Agent[]>([]);
const skills = ref<SkillSummary[]>([]);
const tags = ref<TagSummary[]>([]);
const loading = ref(false);
const error = ref<string | null>(null);

const searchQuery = ref('');
const selectedSkill = ref<string | null>(null);
const selectedTag = ref<string | null>(null);

let debounceTimer: ReturnType<typeof setTimeout> | null = null;

const fetchDiscoveryData = async () => {
  loading.value = true;
  error.value = null;
  try {
    const [agentsRes, skillsRes, tagsRes] = await Promise.all([
      discoveryApi.discover({
        q: searchQuery.value || undefined,
        skill: selectedSkill.value || undefined,
        tag: selectedTag.value || undefined
      }),
      discoveryApi.getSkills(),
      discoveryApi.getTags()
    ]);
    agents.value = agentsRes.data;
    skills.value = skillsRes.data;
    tags.value = tagsRes.data;
  } catch (err: any) {
    error.value = err.response?.data?.message || 'Failed to load discovery data';
  } finally {
    loading.value = false;
  }
};

const executeSearch = async () => {
  loading.value = true;
  error.value = null;
  try {
    const res = await discoveryApi.discover({
      q: searchQuery.value || undefined,
      skill: selectedSkill.value || undefined,
      tag: selectedTag.value || undefined
    });
    agents.value = res.data;
  } catch (err: any) {
    error.value = err.response?.data?.message || 'Discovery search failed';
  } finally {
    loading.value = false;
  }
};

const onSearchInput = () => {
  if (debounceTimer) clearTimeout(debounceTimer);
  debounceTimer = setTimeout(() => {
    executeSearch();
  }, 300);
};

const toggleSkillFilter = (skillId: string) => {
  selectedSkill.value = selectedSkill.value === skillId ? null : skillId;
  executeSearch();
};

const toggleTagFilter = (tag: string) => {
  selectedTag.value = selectedTag.value === tag ? null : tag;
  executeSearch();
};

const clearAllFilters = () => {
  searchQuery.value = '';
  selectedSkill.value = null;
  selectedTag.value = null;
  executeSearch();
};

onMounted(() => {
  fetchDiscoveryData();
});

defineExpose({
  agents,
  skills,
  tags,
  loading,
  error,
  searchQuery,
  selectedSkill,
  selectedTag,
  fetchDiscoveryData,
  executeSearch,
  onSearchInput,
  toggleSkillFilter,
  toggleTagFilter,
  clearAllFilters,
});
</script>

<template>
  <div class="max-w-7xl mx-auto p-6">
    <!-- Header -->
    <div class="mb-8">
      <div class="flex items-center gap-2 text-blue-600 mb-1 font-medium text-sm">
        <Compass class="w-4 h-4" />
        <span>Semantic & Capability Discovery</span>
      </div>
      <h1 class="text-3xl font-bold text-gray-900 tracking-tight">Discover AI Agents</h1>
      <p class="text-gray-500 mt-1">
        Find registered agents by declared skills, capability tags, or task intent descriptions.
      </p>
    </div>

    <!-- Search & Filters Container -->
    <div class="bg-white rounded-xl shadow-sm border border-gray-200 p-5 mb-8 space-y-4">
      <!-- Search Input -->
      <div class="relative">
        <Search class="absolute left-3.5 top-1/2 -translate-y-1/2 w-5 h-5 text-gray-400" />
        <input
          v-model="searchQuery"
          @input="onSearchInput"
          type="text"
          placeholder="Describe your task intent (e.g. 'translate markdown documents to Spanish', 'evaluate financial formulas')..."
          class="w-full pl-11 pr-10 py-3 bg-gray-50 border border-gray-200 rounded-lg text-sm text-gray-900 placeholder-gray-400 focus:bg-white focus:ring-2 focus:ring-blue-500 focus:border-blue-500 outline-none transition-all"
        />
        <button
          v-if="searchQuery"
          @click="searchQuery = ''; executeSearch()"
          class="absolute right-3.5 top-1/2 -translate-y-1/2 text-gray-400 hover:text-gray-600 p-1"
        >
          <X class="w-4 h-4" />
        </button>
      </div>

      <!-- Skill Badges Filter Row -->
      <div v-if="skills.length > 0" class="space-y-2 pt-2 border-t border-gray-100">
        <div class="flex items-center justify-between text-xs text-gray-500 font-medium">
          <span class="flex items-center gap-1.5">
            <Sparkles class="w-3.5 h-3.5 text-blue-600" />
            Skills Catalog
          </span>
          <button
            v-if="selectedSkill || selectedTag || searchQuery"
            @click="clearAllFilters"
            class="text-blue-600 hover:underline cursor-pointer"
          >
            Clear all filters
          </button>
        </div>
        <div class="flex flex-wrap gap-2">
          <SkillBadge
            v-for="s in skills"
            :key="s.skillId"
            :label="s.name"
            :count="s.agentCount"
            :active="selectedSkill === s.skillId"
            @click="toggleSkillFilter(s.skillId)"
          />
        </div>
      </div>

      <!-- Tag Badges Filter Row -->
      <div v-if="tags.length > 0" class="space-y-2 pt-2 border-t border-gray-100">
        <div class="text-xs text-gray-500 font-medium flex items-center gap-1.5">
          <SlidersHorizontal class="w-3.5 h-3.5 text-gray-400" />
          Tags
        </div>
        <div class="flex flex-wrap gap-2">
          <SkillBadge
            v-for="t in tags"
            :key="t.tag"
            :label="'#' + t.tag"
            :count="t.count"
            :active="selectedTag === t.tag"
            @click="toggleTagFilter(t.tag)"
          />
        </div>
      </div>
    </div>

    <!-- Active Filters Feedback -->
    <div v-if="selectedSkill || selectedTag" class="flex items-center gap-2 mb-6">
      <span class="text-xs text-gray-500">Active filter:</span>
      <span
        v-if="selectedSkill"
        class="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium bg-blue-50 text-blue-700 border border-blue-200"
      >
        Skill: {{ skills.find(s => s.skillId === selectedSkill)?.name || selectedSkill }}
        <button @click="selectedSkill = null; executeSearch()" class="hover:text-blue-900"><X class="w-3 h-3" /></button>
      </span>
      <span
        v-if="selectedTag"
        class="inline-flex items-center gap-1 px-2.5 py-1 rounded-md text-xs font-medium bg-purple-50 text-purple-700 border border-purple-200"
      >
        Tag: #{{ selectedTag }}
        <button @click="selectedTag = null; executeSearch()" class="hover:text-purple-900"><X class="w-3 h-3" /></button>
      </span>
    </div>

    <!-- Results Header -->
    <div class="flex justify-between items-center mb-6">
      <p class="text-sm text-gray-600 font-medium">
        {{ agents.length }} {{ agents.length === 1 ? 'agent matches' : 'agents match' }} criteria
      </p>
    </div>

    <!-- Error State -->
    <div v-if="error" class="bg-red-50 border-l-4 border-red-500 p-4 mb-6 rounded-r-lg">
      <p class="text-sm text-red-700">{{ error }}</p>
    </div>

    <!-- Loading State -->
    <div v-if="loading && agents.length === 0" class="flex justify-center py-20">
      <div class="animate-spin rounded-full h-8 w-8 border-b-2 border-blue-600"></div>
    </div>

    <!-- Empty State -->
    <div
      v-else-if="agents.length === 0"
      class="text-center py-20 border-2 border-dashed border-gray-200 rounded-xl bg-white"
    >
      <Search class="mx-auto h-12 w-12 text-gray-300 mb-3" />
      <h3 class="text-base font-semibold text-gray-900">No matching agents found</h3>
      <p class="text-sm text-gray-500 mt-1 max-w-sm mx-auto">
        Try adjusting your search terms, removing skill filters, or registering new agents to the hub.
      </p>
      <button
        v-if="selectedSkill || selectedTag || searchQuery"
        @click="clearAllFilters"
        class="mt-4 px-4 py-2 text-sm font-medium text-blue-600 hover:text-blue-700 hover:bg-blue-50 rounded-lg transition-colors"
      >
        Reset filters
      </button>
    </div>

    <!-- Agent Results Grid -->
    <div v-else class="grid grid-cols-1 md:grid-cols-2 lg:grid-cols-3 gap-6">
      <AgentCardComponent
        v-for="agent in agents"
        :key="agent.id"
        :agent="agent"
      />
    </div>
  </div>
</template>
