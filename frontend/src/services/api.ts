import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1',
  headers: {
    'Content-Type': 'application/json'
  }
});

export interface AgentSkill {
  id: string;
  name: string;
  description: string;
  tags: string[];
}

export interface AgentCard {
  name: string;
  description: string;
  url: string;
  version: string;
  skills: AgentSkill[];
  capabilities: Record<string, any>;
  supportedInterfaces: string[];
}

export type AgentStatus = 'HEALTHY' | 'DEGRADED' | 'OFFLINE' | 'UNKNOWN';

export interface Agent {
  id: string;
  name: string;
  description: string;
  url: string;
  version: string;
  providerName: string | null;
  status: AgentStatus;
  authType: string;
  agentCard?: AgentCard | null;
  registeredAt: string;
  lastSeenAt: string | null;
  latencyMs?: number;
}

export interface SkillSummary {
  skillId: string;
  name: string;
  description: string;
  tags: string[];
  agentCount: number;
  agentIds: string[];
}

export interface TagSummary {
  tag: string;
  count: number;
}

export interface DiscoverParams {
  skill?: string;
  tag?: string;
  capability?: string;
  q?: string;
}

export interface HealthCheckItem {
  id: number;
  checkedAt: string;
  status: string;
  latencyMs: number | null;
  errorMessage: string | null;
}

export interface AgentHealthResponse {
  agentId: string;
  agentName: string;
  currentStatus: string;
  lastSeenAt: string | null;
  history: HealthCheckItem[];
}

export interface HubHealthStats {
  totalAgents: number;
  healthyAgents: number;
  degradedAgents: number;
  offlineAgents: number;
  unknownAgents: number;
  averageLatencyMs: number;
}

export interface AgentStatusEvent {
  agentId: string;
  agentName: string;
  status: 'HEALTHY' | 'DEGRADED' | 'OFFLINE' | 'UNKNOWN';
  previousStatus: string;
  latencyMs: number;
  timestamp: string;
  message: string;
}

export const agentsApi = {
  getAll: (page = 0, size = 50) => api.get<Agent[]>('/agents', { params: { page, size } }),
  getById: (id: string) => api.get<Agent>(`/agents/${id}`),
  register: (url: string) => api.post<Agent>('/agents', { url }),
  unregister: (id: string) => api.delete(`/agents/${id}`)
};

export const discoveryApi = {
  discover: (params: DiscoverParams) => api.get<Agent[]>('/discover', { params }),
  getSkills: () => api.get<SkillSummary[]>('/skills'),
  getTags: () => api.get<TagSummary[]>('/tags')
};

export const healthApi = {
  getAgentHealth: (agentId: string, limit = 30) => api.get<AgentHealthResponse>(`/agents/${agentId}/health`, { params: { limit } }),
  triggerCheck: (agentId: string) => api.post<{ agentId: string; status: string; latencyMs: number; error: string | null }>(`/agents/${agentId}/health/check`),
  getStats: () => api.get<HubHealthStats>('/health/stats')
};
