import { describe, it, expect, vi, beforeEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { createPinia, setActivePinia } from 'pinia';
import AgentDetailView from '../AgentDetailView.vue';
import { healthApi, api, type AgentHealthResponse, type Agent } from '../../services/api';
import { useAgentStore } from '../../stores/agentStore';

const mockPush = vi.fn();

vi.mock('vue-router', () => ({
  useRoute: () => ({
    params: { id: 'agent-123' },
  }),
  useRouter: () => ({
    push: mockPush,
  }),
}));

const mockAgentFixture: Agent = {
  id: 'agent-123',
  name: 'WeatherAssistant',
  description: 'Meteorological assistant',
  url: 'https://weather.agent.io',
  version: '2.0.0',
  providerName: 'MeteoCorp',
  status: 'HEALTHY',
  authType: 'BEARER',
  registeredAt: '2026-09-30T10:00:00Z',
  lastSeenAt: '2026-10-01T12:00:00Z',
  latencyMs: 95,
  agentCard: {
    name: 'WeatherAssistant',
    description: 'Meteorological assistant',
    url: 'https://weather.agent.io',
    version: '2.0.0',
    skills: [],
    capabilities: {},
    supportedInterfaces: ['REST'],
  },
};

const mockHealthData: AgentHealthResponse = {
  agentId: 'agent-123',
  agentName: 'WeatherAssistant',
  currentStatus: 'HEALTHY',
  lastSeenAt: '2026-10-01T12:00:00Z',
  history: [
    {
      id: 1,
      checkedAt: '2026-10-01T12:00:00Z',
      status: 'HEALTHY',
      latencyMs: 95,
      errorMessage: null,
    },
    {
      id: 2,
      checkedAt: '2026-10-01T11:55:00Z',
      status: 'DEGRADED',
      latencyMs: 220,
      errorMessage: null,
    },
    {
      id: 3,
      checkedAt: '2026-10-01T11:50:00Z',
      status: 'OFFLINE',
      latencyMs: null,
      errorMessage: 'Socket timeout after 3000ms',
    },
  ],
};

describe('AgentDetailView Component', () => {
  beforeEach(() => {
    setActivePinia(createPinia());
    vi.clearAllMocks();
    vi.spyOn(api, 'get').mockImplementation(async (url: string) => {
      if (url === '/agents/agent-123') {
        return { data: mockAgentFixture } as any;
      }
      return { data: [] } as any;
    });
  });

  describe('Initial Health Telemetry & Header Presentation', () => {
    it('renders agent metadata, computed stats, sparkline history, and probe table', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({ data: mockHealthData } as any);

      const pinia = createPinia();
      const wrapper = mount(AgentDetailView, {
        global: { plugins: [pinia] },
      });

      const store = useAgentStore();
      store.agents = [mockAgentFixture];

      await flushPromises();

      expect(wrapper.text()).toContain('WeatherAssistant');
      expect(wrapper.text()).toContain('https://weather.agent.io');
      expect(wrapper.text()).toContain('v2.0.0');
      expect(wrapper.text()).toContain('BEARER');

      // Telemetry stats
      expect(wrapper.text()).toContain('67%'); // 2 of 3 are healthy/degraded = 67%
      expect(wrapper.text()).toContain('158 ms'); // avg of 95 and 220 = 157.5 -> 158
      expect(wrapper.text()).toContain('Max: 220ms');

      // Table rows
      expect(wrapper.text()).toContain('95 ms');
      expect(wrapper.text()).toContain('220 ms');
      expect(wrapper.text()).toContain('—'); // null latency
      expect(wrapper.text()).toContain('Socket timeout after 3000ms');
    });

    it('navigates back to root registry when clicking "Back to Registry" button', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({ data: mockHealthData } as any);

      const wrapper = mount(AgentDetailView);
      await flushPromises();

      const backBtn = wrapper.find('button');
      await backBtn.trigger('click');

      expect(mockPush).toHaveBeenCalledWith('/');
    });

    it('renders error banner when fetchHealthHistory fails with response data message', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockRejectedValue({
        response: { data: { message: 'Agent health record not found in database' } },
      });

      const wrapper = mount(AgentDetailView);
      await flushPromises();

      expect(wrapper.find('.bg-red-50').exists()).toBe(true);
      expect(wrapper.text()).toContain('Agent health record not found in database');
    });

    it('renders fallback error message when fetchHealthHistory fails with generic exception', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockRejectedValue(new Error('Network error'));

      const wrapper = mount(AgentDetailView);
      await flushPromises();

      expect(wrapper.text()).toContain('Failed to fetch agent health data');
    });
  });

  describe('Manual Health Probe Execution', () => {
    it('triggers health probe action in store, refreshes history, and updates UI', async () => {
      const getHealthSpy = vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({ data: mockHealthData } as any);

      const wrapper = mount(AgentDetailView);
      const store = useAgentStore();
      const probeSpy = vi.spyOn(store, 'triggerHealthProbe').mockResolvedValue({
        agentId: 'agent-123',
        status: 'HEALTHY',
        latencyMs: 40,
        error: null,
      } as any);

      await flushPromises();

      const probeBtn = wrapper.find('button.bg-blue-600');
      expect(probeBtn.text()).toBe('Probe Health Now');

      await probeBtn.trigger('click');
      await flushPromises();

      expect(probeSpy).toHaveBeenCalledWith('agent-123');
      expect(getHealthSpy).toHaveBeenCalledTimes(2); // initial load + refresh after probe
    });

    it('displays error banner when manual health probe triggers an API error with message', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({ data: mockHealthData } as any);

      const wrapper = mount(AgentDetailView);
      const store = useAgentStore();
      vi.spyOn(store, 'triggerHealthProbe').mockRejectedValue({
        response: { data: { message: 'Agent socket connection timed out' } },
      });

      await flushPromises();

      const probeBtn = wrapper.find('button.bg-blue-600');
      await probeBtn.trigger('click');
      await flushPromises();

      expect(wrapper.text()).toContain('Agent socket connection timed out');
    });

    it('displays fallback error message when manual probe fails with generic exception', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({ data: mockHealthData } as any);

      const wrapper = mount(AgentDetailView);
      const store = useAgentStore();
      vi.spyOn(store, 'triggerHealthProbe').mockRejectedValue(new Error('Unknown failure'));

      await flushPromises();

      const probeBtn = wrapper.find('button.bg-blue-600');
      await probeBtn.trigger('click');
      await flushPromises();

      expect(wrapper.text()).toContain('Manual probe failed');
    });
  });

  describe('Status Badge Variants and Fallbacks', () => {
    const statusCases = [
      { status: 'HEALTHY', expectedText: 'HEALTHY', expectedDot: 'bg-emerald-500' },
      { status: 'DEGRADED', expectedText: 'DEGRADED', expectedDot: 'bg-amber-500' },
      { status: 'OFFLINE', expectedText: 'OFFLINE', expectedDot: 'bg-rose-500' },
      { status: 'UNKNOWN', expectedText: 'UNKNOWN', expectedDot: 'bg-gray-400' },
    ];

    statusCases.forEach(({ status, expectedText, expectedDot }) => {
      it(`computes statusBadge tokens accurately for status "${status}"`, async () => {
        vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({
          data: {
            ...mockHealthData,
            currentStatus: status,
          },
        } as any);

        const wrapper = mount(AgentDetailView);
        await flushPromises();

        const badge = (wrapper.vm as any).statusBadge;
        expect(badge.text).toBe(expectedText);
        expect(badge.dot).toBe(expectedDot);
      });
    });

    it('falls back to agent.status from store when healthData is null', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockReturnValue(new Promise(() => {}) as any);

      const wrapper = mount(AgentDetailView);
      const store = useAgentStore();
      store.agents = [{ ...mockAgentFixture, status: 'DEGRADED' }];
      await wrapper.vm.$nextTick();

      const badge = (wrapper.vm as any).statusBadge;
      expect(badge.text).toBe('DEGRADED');
      expect(badge.dot).toBe('bg-amber-500');
    });

    it('falls back to UNKNOWN when both healthData and store agent are absent', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockReturnValue(new Promise(() => {}) as any);

      const wrapper = mount(AgentDetailView);
      const store = useAgentStore();
      store.agents = [];
      await wrapper.vm.$nextTick();

      const badge = (wrapper.vm as any).statusBadge;
      expect(badge.text).toBe('UNKNOWN');
      expect(badge.dot).toBe('bg-gray-400');
    });
  });

  describe('Empty and Edge-Case Telemetry History Computations', () => {
    it('computes zero latency and default 100% uptime when health history is completely empty', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({
        data: {
          agentId: 'agent-123',
          agentName: 'FreshAgent',
          currentStatus: 'UNKNOWN',
          lastSeenAt: null,
          history: [],
        },
      } as any);

      const wrapper = mount(AgentDetailView);
      await flushPromises();

      expect((wrapper.vm as any).avgLatency).toBe(0);
      expect((wrapper.vm as any).maxLatency).toBe(100);
      expect((wrapper.vm as any).uptimePercent).toBe(100);

      expect(wrapper.text()).toContain('No probe metrics recorded yet.');
      expect(wrapper.text()).toContain('No health check logs found for this agent.');
      expect(wrapper.text()).toContain('Never');
      expect(wrapper.text()).toContain('Awaiting first probe');
    });

    it('computes 0 avgLatency when all history items have null latencyMs', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({
        data: {
          agentId: 'agent-123',
          agentName: 'OfflineAgent',
          currentStatus: 'OFFLINE',
          lastSeenAt: null,
          history: [
            { id: 1, checkedAt: '2026-10-01T12:00:00Z', status: 'OFFLINE', latencyMs: null, errorMessage: 'Timeout' },
            { id: 2, checkedAt: '2026-10-01T11:55:00Z', status: 'OFFLINE', latencyMs: null, errorMessage: 'Timeout' },
          ],
        },
      } as any);

      const wrapper = mount(AgentDetailView);
      await flushPromises();

      expect((wrapper.vm as any).avgLatency).toBe(0);
      expect((wrapper.vm as any).uptimePercent).toBe(0);
    });

    it('returns default 100 maxLatency when all history items have 0 latency', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({
        data: {
          agentId: 'agent-123',
          agentName: 'ZeroLatencyAgent',
          currentStatus: 'HEALTHY',
          lastSeenAt: '2026-10-01T12:00:00Z',
          history: [
            { id: 1, checkedAt: '2026-10-01T12:00:00Z', status: 'HEALTHY', latencyMs: 0, errorMessage: null },
          ],
        },
      } as any);

      const wrapper = mount(AgentDetailView);
      await flushPromises();

      expect((wrapper.vm as any).maxLatency).toBe(100);
    });

    it('falls back to default version v1.0.0 and Auth: NONE when agent object has empty fields', async () => {
      vi.spyOn(healthApi, 'getAgentHealth').mockResolvedValue({
        data: {
          ...mockHealthData,
          agentName: 'MinimalAgent',
        },
      } as any);

      const wrapper = mount(AgentDetailView);
      const store = useAgentStore();
      store.agents = [
        {
          ...mockAgentFixture,
          name: 'MinimalAgent',
          version: '',
          authType: '',
        },
      ];

      await flushPromises();

      expect(wrapper.text()).toContain('Version: v1.0.0');
      expect(wrapper.text()).toContain('Auth: NONE');
    });
  });
});
