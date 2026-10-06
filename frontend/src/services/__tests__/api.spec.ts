import { describe, it, expect, beforeAll, afterEach, afterAll } from 'vitest';
import { setupServer } from 'msw/node';
import { http, HttpResponse } from 'msw';
import {
  api,
  discoveryApi,
  healthApi,
  type Agent,
  type SkillSummary,
  type TagSummary,
  type AgentHealthResponse,
  type HubHealthStats,
} from '../api';

const baseURL = 'http://localhost:8080/api/v1';

const sampleAgentFixture: Agent = {
  id: 'a0000000-0000-0000-0000-000000000001',
  name: 'StockAgent',
  description: 'Financial market ticker tracker',
  url: 'https://stocks.agent.net',
  version: '1.0.0',
  providerName: 'FinCorp',
  status: 'HEALTHY',
  authType: 'API_KEY',
  registeredAt: '2026-09-30T10:00:00Z',
  lastSeenAt: '2026-09-30T10:05:00Z',
  latencyMs: 42,
  agentCard: {
    name: 'StockAgent',
    description: 'Financial market ticker tracker',
    url: 'https://stocks.agent.net',
    version: '1.0.0',
    skills: [
      { id: 'stock_quote', name: 'Stock Quote', description: 'Real-time stock price', tags: ['finance', 'stocks'] },
    ],
    capabilities: { realtime: true },
    supportedInterfaces: ['REST'],
  },
};

const sampleHealthResponse: AgentHealthResponse = {
  agentId: 'a0000000-0000-0000-0000-000000000001',
  agentName: 'StockAgent',
  currentStatus: 'HEALTHY',
  lastSeenAt: '2026-09-30T10:05:00Z',
  history: [
    {
      id: 1,
      checkedAt: '2026-09-30T10:05:00Z',
      status: 'HEALTHY',
      latencyMs: 42,
      errorMessage: null,
    },
  ],
};

const sampleHubStats: HubHealthStats = {
  totalAgents: 10,
  healthyAgents: 8,
  degradedAgents: 1,
  offlineAgents: 1,
  unknownAgents: 0,
  averageLatencyMs: 38.4,
};

const server = setupServer(
  http.get(`${baseURL}/agents`, () => {
    return HttpResponse.json([sampleAgentFixture]);
  }),

  http.post(`${baseURL}/agents`, async ({ request }) => {
    const body = (await request.json()) as { url: string };
    if (!body.url || !body.url.startsWith('http')) {
      return HttpResponse.json({ error: 'Validation Failed', message: 'Invalid URL' }, { status: 400 });
    }
    return HttpResponse.json(sampleAgentFixture, { status: 201 });
  }),

  http.get(`${baseURL}/discover`, ({ request }) => {
    const url = new URL(request.url);
    const tag = url.searchParams.get('tag');
    const skill = url.searchParams.get('skill');
    const q = url.searchParams.get('q');

    if (tag === 'finance' || skill === 'stock_quote' || q === 'stock') {
      return HttpResponse.json([sampleAgentFixture]);
    }
    return HttpResponse.json([]);
  }),

  http.get(`${baseURL}/skills`, () => {
    const skills: SkillSummary[] = [
      {
        skillId: 'stock_quote',
        name: 'Stock Quote',
        description: 'Real-time stock price',
        tags: ['finance'],
        agentCount: 1,
        agentIds: ['a0000000-0000-0000-0000-000000000001'],
      },
    ];
    return HttpResponse.json(skills);
  }),

  http.get(`${baseURL}/tags`, () => {
    const tags: TagSummary[] = [{ tag: 'finance', count: 1 }];
    return HttpResponse.json(tags);
  }),

  http.get(`${baseURL}/agents/:agentId/health`, ({ request, params }) => {
    const url = new URL(request.url);
    const limit = url.searchParams.get('limit');
    if (params.agentId === 'invalid-agent') {
      return HttpResponse.json({ message: 'Agent not found' }, { status: 404 });
    }
    return HttpResponse.json({
      ...sampleHealthResponse,
      agentId: params.agentId as string,
      limitApplied: limit ? Number(limit) : 30,
    });
  }),

  http.post(`${baseURL}/agents/:agentId/health/check`, ({ params }) => {
    if (params.agentId === 'unreachable-agent') {
      return HttpResponse.json(
        { agentId: params.agentId, status: 'OFFLINE', latencyMs: 3000, error: 'Connection timeout' },
        { status: 504 }
      );
    }
    return HttpResponse.json({
      agentId: params.agentId,
      status: 'HEALTHY',
      latencyMs: 25,
      error: null,
    });
  }),

  http.get(`${baseURL}/health/stats`, () => {
    return HttpResponse.json(sampleHubStats);
  })
);

beforeAll(() => server.listen({ onUnhandledRequest: 'error' }));
afterEach(() => server.resetHandlers());
afterAll(() => server.close());

describe('API Network Contracts with MSW', () => {
  describe('Agents CRUD Seams', () => {
    it('GET /agents returns registered agents array contract', async () => {
      const response = await api.get<Agent[]>('/agents');
      expect(response.status).toBe(200);
      expect(response.data).toHaveLength(1);
      expect(response.data[0].id).toBe(sampleAgentFixture.id);
      expect(response.data[0].name).toBe('StockAgent');
    });

    it('POST /agents registers agent and returns 201 Created payload', async () => {
      const response = await api.post<Agent>('/agents', { url: 'https://stocks.agent.net' });
      expect(response.status).toBe(201);
      expect(response.data.id).toBe(sampleAgentFixture.id);
    });

    it('POST /agents rejects invalid agent URLs with 400 Bad Request', async () => {
      await expect(api.post('/agents', { url: 'invalid-url' })).rejects.toMatchObject({
        response: {
          status: 400,
          data: { message: 'Invalid URL' },
        },
      });
    });
  });

  describe('discoveryApi Endpoints', () => {
    it('discover queries with multi-criteria search params (tag, skill, q)', async () => {
      const tagResponse = await discoveryApi.discover({ tag: 'finance' });
      expect(tagResponse.status).toBe(200);
      expect(tagResponse.data).toHaveLength(1);

      const skillResponse = await discoveryApi.discover({ skill: 'stock_quote' });
      expect(skillResponse.status).toBe(200);
      expect(skillResponse.data).toHaveLength(1);

      const emptyResponse = await discoveryApi.discover({ tag: 'nonexistent' });
      expect(emptyResponse.status).toBe(200);
      expect(emptyResponse.data).toHaveLength(0);
    });

    it('getSkills returns aggregated skill summary contracts', async () => {
      const response = await discoveryApi.getSkills();
      expect(response.status).toBe(200);
      expect(response.data).toHaveLength(1);
      expect(response.data[0].skillId).toBe('stock_quote');
      expect(response.data[0].agentCount).toBe(1);
      expect(response.data[0].tags).toContain('finance');
    });

    it('getTags returns list of tag summaries with frequency counts', async () => {
      const response = await discoveryApi.getTags();
      expect(response.status).toBe(200);
      expect(response.data).toEqual([{ tag: 'finance', count: 1 }]);
    });
  });

  describe('healthApi Endpoints', () => {
    it('getAgentHealth queries agent health with custom limit', async () => {
      const response = await healthApi.getAgentHealth('a0000000-0000-0000-0000-000000000001', 10);
      expect(response.status).toBe(200);
      expect(response.data.agentId).toBe('a0000000-0000-0000-0000-000000000001');
      expect(response.data.currentStatus).toBe('HEALTHY');
      expect(response.data.history).toHaveLength(1);
      expect((response.data as any).limitApplied).toBe(10);
    });

    it('getAgentHealth defaults to limit=30 when omitted', async () => {
      const response = await healthApi.getAgentHealth('a0000000-0000-0000-0000-000000000001');
      expect(response.status).toBe(200);
      expect((response.data as any).limitApplied).toBe(30);
    });

    it('triggerCheck performs on-demand health probe for an agent', async () => {
      const response = await healthApi.triggerCheck('a0000000-0000-0000-0000-000000000001');
      expect(response.status).toBe(200);
      expect(response.data.agentId).toBe('a0000000-0000-0000-0000-000000000001');
      expect(response.data.status).toBe('HEALTHY');
      expect(response.data.latencyMs).toBe(25);
      expect(response.data.error).toBeNull();
    });

    it('getStats returns hub-wide health statistics and metrics', async () => {
      const response = await healthApi.getStats();
      expect(response.status).toBe(200);
      expect(response.data.totalAgents).toBe(10);
      expect(response.data.healthyAgents).toBe(8);
      expect(response.data.degradedAgents).toBe(1);
      expect(response.data.offlineAgents).toBe(1);
      expect(response.data.averageLatencyMs).toBe(38.4);
    });
  });
});
