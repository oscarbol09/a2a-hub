import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount } from '@vue/test-utils';
import AgentCard from '../AgentCard.vue';
import type { Agent, AgentStatus } from '../../services/api';

const mockPush = vi.fn();

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: mockPush,
  }),
}));

const baseAgentFixture: Agent = {
  id: '550e8400-e29b-41d4-a716-446655440000',
  name: 'WeatherAgent',
  description: 'Provides real-time meteorological forecasts and climate metrics.',
  url: 'https://weather.agent.internal',
  version: '1.2.0',
  providerName: 'OpenWeather',
  status: 'HEALTHY',
  authType: 'BEARER',
  registeredAt: '2026-09-28T10:00:00Z',
  lastSeenAt: '2026-09-28T12:30:00Z',
  latencyMs: 42,
  agentCard: {
    name: 'WeatherAgent',
    description: 'Provides real-time meteorological forecasts and climate metrics.',
    url: 'https://weather.agent.internal',
    version: '1.2.0',
    skills: [
      { id: 'get_weather', name: 'Get Weather', description: 'Fetches weather', tags: ['weather'] },
      { id: 'forecast_7d', name: '7-Day Forecast', description: '7-day weather forecast', tags: ['forecast'] },
      { id: 'air_quality', name: 'Air Quality', description: 'Air quality index', tags: ['environment'] },
      { id: 'storm_radar', name: 'Storm Radar', description: 'Real-time storm radar', tags: ['radar'] },
      { id: 'uv_index', name: 'UV Index', description: 'Solar ultraviolet monitoring', tags: ['radiation'] },
    ],
    capabilities: { streaming: true },
    supportedInterfaces: ['JSON-RPC', 'REST'],
  },
};

describe('AgentCard Component', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  describe('Core Identity & Data Rendering', () => {
    it('renders agent name, url, description, version, and latency badge accurately', () => {
      const wrapper = mount(AgentCard, {
        props: { agent: baseAgentFixture },
      });

      expect(wrapper.find('h3').text()).toBe('WeatherAgent');
      expect(wrapper.find('p[title="https://weather.agent.internal"]').text()).toBe('https://weather.agent.internal');
      expect(wrapper.text()).toContain('Provides real-time meteorological forecasts and climate metrics.');
      expect(wrapper.text()).toContain('v1.2.0');
      expect(wrapper.text()).toContain('BEARER');
      expect(wrapper.text()).toContain('42ms');
    });

    it('falls back to default labels when optional fields are empty or null', () => {
      const minimalAgent: Agent = {
        ...baseAgentFixture,
        description: '',
        version: '',
        latencyMs: undefined,
        agentCard: undefined,
      };

      const wrapper = mount(AgentCard, {
        props: { agent: minimalAgent },
      });

      expect(wrapper.text()).toContain('No description provided.');
      expect(wrapper.text()).toContain('v1.0');
      expect(wrapper.text()).toContain('Skills (0)');
      expect(wrapper.text()).not.toContain('ms');
    });

    it('formats timestamp as "Seen [time]" when lastSeenAt is present', () => {
      const seenTime = new Date('2026-09-28T12:30:00Z').toLocaleTimeString();
      const wrapper = mount(AgentCard, {
        props: { agent: baseAgentFixture },
      });

      expect(wrapper.text()).toContain(`Seen ${seenTime}`);
    });

    it('formats timestamp as "Registered [date]" when lastSeenAt is null', () => {
      const registeredDate = new Date('2026-09-28T10:00:00Z').toLocaleDateString();
      const unregisteredAgent: Agent = {
        ...baseAgentFixture,
        lastSeenAt: null,
      };

      const wrapper = mount(AgentCard, {
        props: { agent: unregisteredAgent },
      });

      expect(wrapper.text()).toContain(`Registered ${registeredDate}`);
    });
  });

  describe('Status Badge Color Variants & Ping Indicators', () => {
    const statusTestCases: Array<{
      status: AgentStatus | string;
      expectedBg: string;
      expectedText: string;
      expectedDot: string;
      expectedComputedPing: string;
      hasPing: boolean;
      expectedPingColor?: string;
    }> = [
      {
        status: 'HEALTHY',
        expectedBg: 'bg-emerald-50',
        expectedText: 'text-emerald-700',
        expectedDot: 'bg-emerald-500',
        expectedComputedPing: 'bg-emerald-400',
        hasPing: true,
        expectedPingColor: 'bg-emerald-400',
      },
      {
        status: 'DEGRADED',
        expectedBg: 'bg-amber-50',
        expectedText: 'text-amber-700',
        expectedDot: 'bg-amber-500',
        expectedComputedPing: 'bg-amber-400',
        hasPing: true,
        expectedPingColor: 'bg-amber-400',
      },
      {
        status: 'OFFLINE',
        expectedBg: 'bg-rose-50',
        expectedText: 'text-rose-700',
        expectedDot: 'bg-rose-500',
        expectedComputedPing: 'bg-rose-400',
        hasPing: false,
      },
      {
        status: 'UNKNOWN' as any,
        expectedBg: 'bg-gray-50',
        expectedText: 'text-gray-700',
        expectedDot: 'bg-gray-400',
        expectedComputedPing: 'bg-gray-300',
        hasPing: false,
      },
    ];

    statusTestCases.forEach(({ status, expectedBg, expectedText, expectedDot, expectedComputedPing, hasPing, expectedPingColor }) => {
      it(`applies expected visual tokens and ping states for status: "${status}"`, () => {
        const agentWithStatus: Agent = {
          ...baseAgentFixture,
          status: status as AgentStatus,
        };

        const wrapper = mount(AgentCard, {
          props: { agent: agentWithStatus },
        });

        expect((wrapper.vm as any).statusPingColor).toBe(expectedComputedPing);
        expect((wrapper.vm as any).statusColor).toBe(expectedDot);

        const statusBadge = wrapper.find('.rounded-full.border');
        expect(statusBadge.classes()).toContain(expectedBg);
        expect(statusBadge.classes()).toContain(expectedText);

        const staticDot = wrapper.find(`span.relative.inline-flex.rounded-full.${expectedDot}`);
        expect(staticDot.exists()).toBe(true);

        const pingSpan = wrapper.find('.animate-ping');
        if (hasPing) {
          expect(pingSpan.exists()).toBe(true);
          if (expectedPingColor) {
            expect(pingSpan.classes()).toContain(expectedPingColor);
          }
        } else {
          expect(pingSpan.exists()).toBe(false);
        }
      });
    });
  });

  describe('Skills List & Overflow Caps', () => {
    it('renders first 3 skills with descriptions as tooltip and computes "+N more" badge', () => {
      const wrapper = mount(AgentCard, {
        props: { agent: baseAgentFixture },
      });

      expect(wrapper.text()).toContain('Skills (5)');
      expect(wrapper.text()).toContain('Get Weather');
      expect(wrapper.text()).toContain('7-Day Forecast');
      expect(wrapper.text()).toContain('Air Quality');
      expect(wrapper.text()).not.toContain('Storm Radar');
      expect(wrapper.text()).not.toContain('UV Index');
      expect(wrapper.text()).toContain('+2 more');

      const weatherBadge = wrapper.find('span[title="Fetches weather"]');
      expect(weatherBadge.exists()).toBe(true);
      expect(weatherBadge.text()).toBe('Get Weather');
    });

    it('does not render "+N more" badge when agent has 3 or fewer skills', () => {
      const lowSkillsAgent: Agent = {
        ...baseAgentFixture,
        agentCard: {
          ...baseAgentFixture.agentCard!,
          skills: [
            { id: '1', name: 'Skill A', description: 'Desc A', tags: ['tagA'] },
            { id: '2', name: 'Skill B', description: 'Desc B', tags: ['tagB'] },
          ],
        },
      };

      const wrapper = mount(AgentCard, {
        props: { agent: lowSkillsAgent },
      });

      expect(wrapper.text()).toContain('Skills (2)');
      expect(wrapper.text()).toContain('Skill A');
      expect(wrapper.text()).toContain('Skill B');
      expect(wrapper.text()).not.toContain('more');
    });
  });

  describe('Interactive Actions & Navigation Dispatch', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('navigates to agent detail view when root card is clicked', async () => {
      const wrapper = mount(AgentCard, {
        props: { agent: baseAgentFixture },
      });

      await wrapper.trigger('click');

      expect(mockPush).toHaveBeenCalledTimes(1);
      expect(mockPush).toHaveBeenCalledWith(`/agents/${baseAgentFixture.id}`);
    });

    it('dispatches quick probe event, triggers loading spinner state, and resets after timeout', async () => {
      const wrapper = mount(AgentCard, {
        props: { agent: baseAgentFixture },
      });

      const probeBtn = wrapper.find('button[title="Probe health now"]');
      expect(probeBtn.attributes('disabled')).toBeUndefined();

      await probeBtn.trigger('click');

      // Emitted probe event with agent id
      expect(wrapper.emitted('probe')).toBeTruthy();
      expect(wrapper.emitted('probe')![0]).toEqual([baseAgentFixture.id]);

      // Button is temporarily disabled and spinning during probing
      expect(probeBtn.attributes('disabled')).toBeDefined();
      const spinningIcon = wrapper.find('.animate-spin');
      expect(spinningIcon.exists()).toBe(true);

      // Card click navigation should not be triggered due to stopPropagation
      expect(mockPush).not.toHaveBeenCalled();

      // Fast-forward 1200ms probe animation timer
      vi.advanceTimersByTime(1200);
      await wrapper.vm.$nextTick();

      // Probe button is re-enabled and spinner ceases
      expect(probeBtn.attributes('disabled')).toBeUndefined();
      expect(wrapper.find('.animate-spin').exists()).toBe(false);
    });

    it('emits unregister event when delete action button is clicked without triggering card navigation', async () => {
      const wrapper = mount(AgentCard, {
        props: { agent: baseAgentFixture },
      });

      const unregisterBtn = wrapper.find('button[title="Unregister Agent"]');
      expect(unregisterBtn.exists()).toBe(true);

      await unregisterBtn.trigger('click');

      expect(wrapper.emitted('unregister')).toHaveLength(1);
      expect(mockPush).not.toHaveBeenCalled();
    });
  });
});
