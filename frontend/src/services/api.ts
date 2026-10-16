import axios from 'axios';

export const api = axios.create({
  baseURL: import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1',
  headers: {
    'Content-Type': 'application/json'
  }
});

api.interceptors.request.use((config) => {
  const apiKey = typeof window !== 'undefined' ? localStorage.getItem('a2a_api_key') : null;
  const keyToUse = apiKey || (import.meta.env ? import.meta.env.VITE_API_KEY : undefined);
  if (keyToUse) {
    config.headers['X-API-Key'] = keyToUse;
  }
  return config;
});

export const setApiKey = (key: string | null): void => {
  if (typeof window === 'undefined') return;
  if (key) {
    localStorage.setItem('a2a_api_key', key);
  } else {
    localStorage.removeItem('a2a_api_key');
  }
};

export const getApiKey = (): string | null => {
  const apiKey = typeof window !== 'undefined' ? localStorage.getItem('a2a_api_key') : null;
  return apiKey || (import.meta.env ? import.meta.env.VITE_API_KEY : null) || null;
};

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
  similarityScore?: number;
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
  discoverSemantic: (q: string, limit = 20) => api.get<Agent[]>('/discover/semantic', { params: { q, limit } }),
  getSkills: () => api.get<SkillSummary[]>('/skills'),
  getTags: () => api.get<TagSummary[]>('/tags')
};

export const healthApi = {
  getAgentHealth: (agentId: string, limit = 30) => api.get<AgentHealthResponse>(`/agents/${agentId}/health`, { params: { limit } }),
  triggerCheck: (agentId: string) => api.post<{ agentId: string; status: string; latencyMs: number; error: string | null }>(`/agents/${agentId}/health/check`),
  getStats: () => api.get<HubHealthStats>('/health/stats')
};

export interface TaskDto {
  id: string;
  agentId: string;
  agentName: string;
  contextId: string;
  state: string;
  request: any;
  response: any;
  errorDetail: string;
  createdAt: string;
  updatedAt: string;
}

export interface SubmitTaskRequest {
  agentId: string;
  contextId?: string;
  payload: Record<string, any>;
}

export const tasksApi = {
  submitTask: (req: SubmitTaskRequest) => api.post<TaskDto>('/tasks', req),
  getTask: (id: string) => api.get<TaskDto>(`/tasks/${id}`),
  getTasksByAgent: (agentId: string) => api.get<TaskDto[]>(`/tasks/agent/${agentId}`)
};

export interface AiSuggestionRequest {
  taskDescription: string;
}

export interface AiSuggestionResponse {
  agentId: string;
  agentName: string;
  reasoning: string;
  source?: 'LLM_REASONING' | 'VECTOR_FALLBACK' | string;
  fallback?: boolean;
  confidenceScore?: number;
  fallbackReason?: string | null;
}

export const orchestrationApi = {
  suggest: (req: AiSuggestionRequest) => api.post<AiSuggestionResponse>('/orchestration/suggest', req)
};
