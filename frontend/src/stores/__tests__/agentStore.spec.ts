import { describe, it, expect, beforeEach, afterEach, vi } from 'vitest';
import { setActivePinia, createPinia } from 'pinia';
import { useAgentStore } from '../agentStore';
import { api, healthApi, type Agent, type AgentStatusEvent, type HubHealthStats } from '../../services/api';
import { wsService } from '../../services/websocket';

const mockAgentFixture: Agent = {
  id: 'agent-123',
  name: 'Test Agent',
  description: 'Test Description',
  url: 'https://test.agent.io',
  version: '1.0.0',
  providerName: null,
  status: 'HEALTHY',
  authType: 'NONE',
  registeredAt: '2026-09-30T12:00:00Z',
  lastSeenAt: '2026-09-30T12:05:00Z',
  latencyMs: 35,
  agentCard: {
    name: 'Test Agent',
    description: 'Test Description',
    url: 'https://test.agent.io',
    version: '1.0.0',
    skills: [{ id: 's1', name: 'Skill 1', description: 'Desc 1', tags: ['tag1'] }],
    capabilities: {},
    supportedInterfaces: ['REST'],
  },
};

const mockHubStatsFixture: HubHealthStats = {
  totalAgents: 3,
  healthyAgents: 2,
  degradedAgents: 1,
  offlineAgents: 0,
  unknownAgents: 0,
  averageLatencyMs: 42.5,
};

describe('useAgentStore Pinia Store', () => {
  let wsCallback: ((event: AgentStatusEvent) => void) | null = null;

  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    wsCallback = null;

    vi.spyOn(healthApi, 'getStats').mockResolvedValue({
      data: mockHubStatsFixture,
    } as any);

    vi.spyOn(wsService, 'connect').mockImplementation(() => {
      wsService.isConnected = true;
    });

    vi.spyOn(wsService, 'onStatusChange').mockImplementation((cb: any) => {
      wsCallback = cb;
      return () => {};
    });
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('Initial State & Reactive Properties', () => {
    it('initializes with default empty reactive state', () => {
      const store = useAgentStore();
      expect(store.agents).toEqual([]);
      expect(store.loading).toBe(false);
      expect(store.error).toBeNull();
      expect(store.hubStats).toBeNull();
      expect(store.wsConnected).toBe(false);
      expect(store.recentEvents).toEqual([]);
    });
  });

  describe('fetchAgents Action', () => {
    it('populates agents list and resets loading and error states on success', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'get').mockResolvedValueOnce({ data: [mockAgentFixture] });

      const promise = store.fetchAgents();
      expect(store.loading).toBe(true);
      expect(store.error).toBeNull();

      await promise;

      expect(store.agents).toEqual([mockAgentFixture]);
      expect(store.loading).toBe(false);
      expect(store.error).toBeNull();
    });

    it('sets error from response data message on API rejection', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'get').mockRejectedValueOnce({
        response: { data: { message: 'Failed to reach database' } },
      });

      await store.fetchAgents();

      expect(store.agents).toEqual([]);
      expect(store.error).toBe('Failed to reach database');
      expect(store.loading).toBe(false);
    });

    it('falls back to default error message when response data does not provide a message', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'get').mockRejectedValueOnce(new Error('Network error'));

      await store.fetchAgents();

      expect(store.agents).toEqual([]);
      expect(store.error).toBe('Failed to fetch agents');
      expect(store.loading).toBe(false);
    });
  });

  describe('fetchHubStats Action', () => {
    it('updates hubStats state with data returned by healthApi.getStats()', async () => {
      const store = useAgentStore();
      await store.fetchHubStats();

      expect(store.hubStats).toEqual(mockHubStatsFixture);
    });

    it('catches and logs error to console when healthApi.getStats() fails without throwing', async () => {
      const consoleSpy = vi.spyOn(console, 'error').mockImplementation(() => {});
      const errorObj = new Error('Stats endpoint 503');
      vi.spyOn(healthApi, 'getStats').mockRejectedValueOnce(errorObj);

      const store = useAgentStore();
      await store.fetchHubStats();

      expect(store.hubStats).toBeNull();
      expect(consoleSpy).toHaveBeenCalledWith('Failed to fetch hub health stats:', errorObj);
    });
  });

  describe('registerAgent Action', () => {
    it('registers new agent, updates list, refreshes hub stats, and returns true', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'post').mockResolvedValueOnce({ data: mockAgentFixture });
      const statsSpy = vi.spyOn(healthApi, 'getStats');

      const result = await store.registerAgent('https://test.agent.io');

      expect(result).toBe(true);
      expect(store.agents).toHaveLength(1);
      expect(store.agents[0]).toEqual(mockAgentFixture);
      expect(store.loading).toBe(false);
      expect(store.error).toBeNull();
      expect(statsSpy).toHaveBeenCalled();
    });

    it('handles registration error with custom backend response message and returns false', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'post').mockRejectedValueOnce({
        response: { data: { message: 'Agent URL already registered' } },
      });

      const result = await store.registerAgent('https://test.agent.io');

      expect(result).toBe(false);
      expect(store.agents).toHaveLength(0);
      expect(store.error).toBe('Agent URL already registered');
      expect(store.loading).toBe(false);
    });

    it('handles registration failure fallback when response has no message and returns false', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'post').mockRejectedValueOnce({});

      const result = await store.registerAgent('https://test.agent.io');

      expect(result).toBe(false);
      expect(store.error).toBe('Failed to register agent');
      expect(store.loading).toBe(false);
    });
  });

  describe('unregisterAgent Action', () => {
    it('removes target agent by ID from local state and refreshes hub stats on success', async () => {
      const store = useAgentStore();
      store.agents = [{ ...mockAgentFixture }, { ...mockAgentFixture, id: 'agent-456' }];
      vi.spyOn(api, 'delete').mockResolvedValueOnce({});
      const statsSpy = vi.spyOn(healthApi, 'getStats');

      await store.unregisterAgent('agent-123');

      expect(store.agents).toHaveLength(1);
      expect(store.agents[0].id).toBe('agent-456');
      expect(statsSpy).toHaveBeenCalled();
    });

    it('sets error from response message when unregistration fails', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'delete').mockRejectedValueOnce({
        response: { data: { message: 'Unauthorized agent unregistration' } },
      });

      await store.unregisterAgent('agent-123');

      expect(store.error).toBe('Unauthorized agent unregistration');
    });

    it('falls back to default error message when unregister fails without specific response message', async () => {
      const store = useAgentStore();
      vi.spyOn(api, 'delete').mockRejectedValueOnce(new Error('Internal server error'));

      await store.unregisterAgent('agent-123');

      expect(store.error).toBe('Failed to unregister agent');
    });
  });

  describe('triggerHealthProbe Action', () => {
    it('updates matching agent status, latency, and lastSeenAt on probe success', async () => {
      const store = useAgentStore();
      store.agents = [{ ...mockAgentFixture, status: 'UNKNOWN', latencyMs: undefined }];

      vi.spyOn(healthApi, 'triggerCheck').mockResolvedValueOnce({
        data: {
          agentId: 'agent-123',
          status: 'HEALTHY',
          latencyMs: 18,
          error: null,
        },
      } as any);

      const probeResult = await store.triggerHealthProbe('agent-123');

      expect(probeResult.status).toBe('HEALTHY');
      expect(store.agents[0].status).toBe('HEALTHY');
      expect(store.agents[0].latencyMs).toBe(18);
      expect(store.agents[0].lastSeenAt).toBeDefined();
    });

    it('does not throw when triggering health probe for an agent not currently in local agents array', async () => {
      const store = useAgentStore();
      store.agents = [];

      vi.spyOn(healthApi, 'triggerCheck').mockResolvedValueOnce({
        data: {
          agentId: 'agent-999',
          status: 'DEGRADED',
          latencyMs: 500,
          error: 'Slow response',
        },
      } as any);

      const probeResult = await store.triggerHealthProbe('agent-999');

      expect(probeResult.status).toBe('DEGRADED');
      expect(store.agents).toHaveLength(0);
    });

    it('sets error state and re-throws when health probe fails with custom message', async () => {
      const store = useAgentStore();
      const customError = {
        response: { data: { message: 'Agent health check timed out after 3000ms' } },
      };
      vi.spyOn(healthApi, 'triggerCheck').mockRejectedValueOnce(customError);

      await expect(store.triggerHealthProbe('agent-123')).rejects.toEqual(customError);
      expect(store.error).toBe('Agent health check timed out after 3000ms');
    });

    it('sets default error state and re-throws when health probe fails without message', async () => {
      const store = useAgentStore();
      const genericError = new Error('Connection refused');
      vi.spyOn(healthApi, 'triggerCheck').mockRejectedValueOnce(genericError);

      await expect(store.triggerHealthProbe('agent-123')).rejects.toEqual(genericError);
      expect(store.error).toBe('Health probe failed');
    });
  });

  describe('initWebSocket & Real-time Telemetry Stream', () => {
    it('establishes WebSocket connection and registers status change listener', () => {
      const store = useAgentStore();

      store.initWebSocket();

      expect(wsService.connect).toHaveBeenCalledTimes(1);
      expect(store.wsConnected).toBe(true);
      expect(wsService.onStatusChange).toHaveBeenCalledWith(expect.any(Function));
      expect(wsCallback).not.toBeNull();
    });

    it('updates matching agent state and refreshes hub stats when status change event arrives', () => {
      const store = useAgentStore();
      store.agents = [{ ...mockAgentFixture, status: 'HEALTHY', latencyMs: 20 }];
      store.initWebSocket();

      const eventPayload: AgentStatusEvent = {
        agentId: 'agent-123',
        agentName: 'Test Agent',
        status: 'DEGRADED',
        previousStatus: 'HEALTHY',
        latencyMs: 120,
        timestamp: '2026-10-05T19:21:00Z',
        message: 'Elevated latency detected',
      };

      wsCallback!(eventPayload);

      expect(store.recentEvents).toHaveLength(1);
      expect(store.recentEvents[0]).toEqual(eventPayload);

      expect(store.agents[0].status).toBe('DEGRADED');
      expect(store.agents[0].latencyMs).toBe(120);
      expect(store.agents[0].lastSeenAt).toBe('2026-10-05T19:21:00Z');
    });

    it('appends event to stream without error even if the agent is not in the local agents list', () => {
      const store = useAgentStore();
      store.agents = [];
      store.initWebSocket();

      const eventPayload: AgentStatusEvent = {
        agentId: 'agent-unlisted',
        agentName: 'Unlisted Agent',
        status: 'OFFLINE',
        previousStatus: 'HEALTHY',
        latencyMs: 0,
        timestamp: '2026-10-05T19:21:30Z',
        message: 'Agent heartbeat lost',
      };

      wsCallback!(eventPayload);

      expect(store.recentEvents).toHaveLength(1);
      expect(store.recentEvents[0]).toEqual(eventPayload);
    });

    it('caps recentEvents stream at 20 entries, dropping the oldest when new events arrive', () => {
      const store = useAgentStore();
      store.initWebSocket();

      // Emit 22 status events
      for (let i = 1; i <= 22; i++) {
        wsCallback!({
          agentId: `agent-${i}`,
          agentName: `Agent ${i}`,
          status: 'HEALTHY',
          previousStatus: 'HEALTHY',
          latencyMs: i * 5,
          timestamp: `2026-10-05T19:20:${i < 10 ? '0' + i : i}Z`,
          message: `Event ${i}`,
        });
      }

      expect(store.recentEvents).toHaveLength(20);
      // Most recent event (22) should be at index 0
      expect(store.recentEvents[0].message).toBe('Event 22');
      // Event 21 should be at index 1
      expect(store.recentEvents[1].message).toBe('Event 21');
      // Oldest retained event (3) should be at index 19 (events 1 and 2 were popped)
      expect(store.recentEvents[19].message).toBe('Event 3');
    });
  });
});
