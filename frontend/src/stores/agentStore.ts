import { defineStore } from 'pinia';
import { api, healthApi, type Agent, type AgentStatusEvent, type HubHealthStats } from '../services/api';
import { wsService } from '../services/websocket';
import { ref } from 'vue';

export const useAgentStore = defineStore('agents', () => {
  const agents = ref<Agent[]>([]);
  const loading = ref(false);
  const error = ref<string | null>(null);
  const hubStats = ref<HubHealthStats | null>(null);
  const wsConnected = ref(false);
  const recentEvents = ref<AgentStatusEvent[]>([]);

  const fetchAgents = async () => {
    loading.value = true;
    error.value = null;
    try {
      const response = await api.get<Agent[]>('/agents');
      agents.value = response.data;
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to fetch agents';
    } finally {
      loading.value = false;
    }
  };

  const fetchAgentById = async (id: string) => {
    try {
      const response = await api.get<Agent>(`/agents/${id}`);
      const index = agents.value.findIndex(a => a.id === id);
      if (index >= 0) {
        agents.value[index] = response.data;
      } else {
        agents.value.push(response.data);
      }
      return response.data;
    } catch (err: any) {
      console.warn(`Failed to fetch agent ${id}:`, err);
      return null;
    }
  };

  const fetchHubStats = async () => {
    try {
      const res = await healthApi.getStats();
      hubStats.value = res.data;
    } catch (err) {
      console.error('Failed to fetch hub health stats:', err);
    }
  };

  const registerAgent = async (url: string) => {
    loading.value = true;
    error.value = null;
    try {
      const response = await api.post<Agent>('/agents', { url });
      agents.value.push(response.data);
      fetchHubStats();
      return true;
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to register agent';
      return false;
    } finally {
      loading.value = false;
    }
  };

  const unregisterAgent = async (id: string) => {
    try {
      await api.delete(`/agents/${id}`);
      agents.value = agents.value.filter(a => a.id !== id);
      fetchHubStats();
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Failed to unregister agent';
    }
  };

  const triggerHealthProbe = async (agentId: string) => {
    try {
      const res = await healthApi.triggerCheck(agentId);
      const agent = agents.value.find(a => a.id === agentId);
      if (agent) {
        agent.status = res.data.status as any;
        agent.latencyMs = res.data.latencyMs;
        agent.lastSeenAt = new Date().toISOString();
      }
      fetchHubStats();
      return res.data;
    } catch (err: any) {
      error.value = err.response?.data?.message || 'Health probe failed';
      throw err;
    }
  };

  let wsInitialized = false;

  const initWebSocket = () => {
    if (wsInitialized) {
      return;
    }
    wsInitialized = true;

    wsService.onConnectionChange(connected => {
      wsConnected.value = connected;
    });

    wsService.connect();
    wsConnected.value = wsService.isConnected;

    wsService.onStatusChange((event: AgentStatusEvent) => {
      // Keep trailing event stream for telemetry feed
      recentEvents.value.unshift(event);
      if (recentEvents.value.length > 20) {
        recentEvents.value.pop();
      }

      // Update agent in local state
      const targetAgent = agents.value.find(a => a.id === event.agentId);
      if (targetAgent) {
        targetAgent.status = event.status;
        targetAgent.latencyMs = event.latencyMs;
        targetAgent.lastSeenAt = event.timestamp;
      }
      
      fetchHubStats();
    });
  };

  return {
    agents,
    loading,
    error,
    hubStats,
    wsConnected,
    recentEvents,
    fetchAgents,
    fetchAgentById,
    fetchHubStats,
    registerAgent,
    unregisterAgent,
    triggerHealthProbe,
    initWebSocket
  };
});
