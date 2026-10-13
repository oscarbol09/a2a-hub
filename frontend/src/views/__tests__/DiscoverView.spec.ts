import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import DiscoverView from '../DiscoverView.vue';
import { discoveryApi, type Agent, type SkillSummary, type TagSummary } from '../../services/api';

vi.mock('vue-router', () => ({
  useRouter: () => ({
    push: vi.fn(),
  }),
}));

const mockAgent1: Agent = {
  id: 'agent-1',
  name: 'WeatherAgent',
  description: 'Weather forecaster for microclimates',
  url: 'https://weather.agent.io',
  version: '1.0.0',
  providerName: null,
  status: 'HEALTHY',
  authType: 'NONE',
  registeredAt: '2026-09-30T10:00:00Z',
  lastSeenAt: '2026-09-30T10:30:00Z',
  latencyMs: 25,
  agentCard: {
    name: 'WeatherAgent',
    description: 'Weather forecaster for microclimates',
    url: 'https://weather.agent.io',
    version: '1.0.0',
    skills: [{ id: 'get_weather', name: 'Get Weather', description: 'Weather', tags: ['weather'] }],
    capabilities: {},
    supportedInterfaces: ['REST'],
  },
};

const mockAgent2: Agent = {
  id: 'agent-2',
  name: 'FinanceAgent',
  description: 'Portfolio analysis and stock telemetry',
  url: 'https://finance.agent.io',
  version: '2.1.0',
  providerName: 'WallSt',
  status: 'DEGRADED',
  authType: 'API_KEY',
  registeredAt: '2026-09-30T11:00:00Z',
  lastSeenAt: null,
  latencyMs: 120,
  agentCard: {
    name: 'FinanceAgent',
    description: 'Portfolio analysis and stock telemetry',
    url: 'https://finance.agent.io',
    version: '2.1.0',
    skills: [{ id: 'stock_eval', name: 'Stock Evaluation', description: 'Stocks', tags: ['finance'] }],
    capabilities: {},
    supportedInterfaces: ['REST'],
  },
};

const mockSkills: SkillSummary[] = [
  { skillId: 'get_weather', name: 'Get Weather', description: 'Live weather', tags: ['weather'], agentCount: 1, agentIds: ['agent-1'] },
  { skillId: 'stock_eval', name: 'Stock Evaluation', description: 'Stock metrics', tags: ['finance'], agentCount: 1, agentIds: ['agent-2'] },
];

const mockTags: TagSummary[] = [
  { tag: 'weather', count: 1 },
  { tag: 'finance', count: 1 },
];

describe('DiscoverView Component', () => {
  beforeEach(() => {
    vi.restoreAllMocks();
  });

  describe('Initial Data Loading & Presentation', () => {
    it('loads and renders initial agents, skills catalog, tag badges, and header metrics', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1, mockAgent2] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      expect(wrapper.text()).toContain('Discover AI Agents');
      expect(wrapper.text()).toContain('WeatherAgent');
      expect(wrapper.text()).toContain('FinanceAgent');
      expect(wrapper.text()).toContain('Get Weather');
      expect(wrapper.text()).toContain('Stock Evaluation');
      expect(wrapper.text()).toContain('#weather');
      expect(wrapper.text()).toContain('#finance');
      expect(wrapper.text()).toContain('2 agents match criteria');
    });

    it('displays loading spinner when initial data is in flight', async () => {
      vi.spyOn(discoveryApi, 'discover').mockReturnValue(new Promise(() => {}) as any);
      vi.spyOn(discoveryApi, 'getSkills').mockReturnValue(new Promise(() => {}) as any);
      vi.spyOn(discoveryApi, 'getTags').mockReturnValue(new Promise(() => {}) as any);

      const wrapper = mount(DiscoverView);
      await wrapper.vm.$nextTick();

      expect(wrapper.find('.animate-spin').exists()).toBe(true);
      expect(wrapper.text()).not.toContain('No matching agents found');
    });

    it('renders error banner when initial fetchDiscoveryData fails with response message', async () => {
      vi.spyOn(discoveryApi, 'discover').mockRejectedValue({
        response: { data: { message: 'Vector database connection lost' } },
      });
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: [] } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      expect(wrapper.find('.bg-red-50').exists()).toBe(true);
      expect(wrapper.text()).toContain('Vector database connection lost');
    });

    it('renders fallback error message when initial fetch fails with generic exception', async () => {
      vi.spyOn(discoveryApi, 'discover').mockRejectedValue(new Error('Network error'));
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: [] } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      expect(wrapper.text()).toContain('Failed to load discovery data');
    });
  });

  describe('Debounced Search Input', () => {
    beforeEach(() => {
      vi.useFakeTimers();
    });

    afterEach(() => {
      vi.useRealTimers();
    });

    it('debounces user search typing by 300ms before executing query', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();
      discoverSpy.mockClear();

      const searchInput = wrapper.find('input[type="text"]');
      await searchInput.setValue('weather forecast');

      // Immediate expectation: timer is running, query has NOT yet been dispatched
      expect(discoverSpy).not.toHaveBeenCalled();

      // Typing more before 300ms resets the debounce timer
      vi.advanceTimersByTime(200);
      await searchInput.setValue('weather forecast live');
      expect(discoverSpy).not.toHaveBeenCalled();

      // Complete the 300ms window
      vi.advanceTimersByTime(300);
      await flushPromises();

      expect(discoverSpy).toHaveBeenCalledTimes(1);
      expect(discoverSpy).toHaveBeenCalledWith(
        expect.objectContaining({ q: 'weather forecast live' })
      );
    });

    it('clears search input and dispatches query immediately when clicking clear button', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      const searchInput = wrapper.find('input[type="text"]');
      await searchInput.setValue('some query');
      await wrapper.vm.$nextTick();

      const clearBtn = wrapper.find('button.absolute.right-3\\.5');
      expect(clearBtn.exists()).toBe(true);

      discoverSpy.mockClear();
      await clearBtn.trigger('click');
      await flushPromises();

      expect((searchInput.element as HTMLInputElement).value).toBe('');
      expect(discoverSpy).toHaveBeenCalledWith(
        expect.objectContaining({ q: undefined })
      );
    });
  });

  describe('Skill and Tag Filtering', () => {
    it('toggles skill filter on and off when clicked repeatedly', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      discoverSpy.mockClear();

      // Find skill badge buttons
      const skillButtons = wrapper.findAllComponents({ name: 'SkillBadge' });
      const weatherSkillBadge = skillButtons[0];

      // Click to select
      await weatherSkillBadge.trigger('click');
      await flushPromises();

      expect(discoverSpy).toHaveBeenCalledWith(expect.objectContaining({ skill: 'get_weather' }));
      expect((wrapper.vm as any).selectedSkill).toBe('get_weather');

      // Active chip should be rendered
      expect(wrapper.text()).toContain('Skill: Get Weather');

      // Click same skill to deselect
      discoverSpy.mockClear();
      await weatherSkillBadge.trigger('click');
      await flushPromises();

      expect(discoverSpy).toHaveBeenCalledWith(expect.objectContaining({ skill: undefined }));
      expect((wrapper.vm as any).selectedSkill).toBeNull();
      expect(wrapper.text()).not.toContain('Skill: Get Weather');
    });

    it('toggles tag filter on and off when clicked', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      discoverSpy.mockClear();

      // Find tag badge buttons (after skill badges)
      const allBadges = wrapper.findAllComponents({ name: 'SkillBadge' });
      const tagBadge = allBadges.find(b => b.props('label') === '#weather')!;

      await tagBadge.trigger('click');
      await flushPromises();

      expect(discoverSpy).toHaveBeenCalledWith(expect.objectContaining({ tag: 'weather' }));
      expect((wrapper.vm as any).selectedTag).toBe('weather');
      expect(wrapper.text()).toContain('Tag: #weather');

      // Deselect tag via badge click
      discoverSpy.mockClear();
      await tagBadge.trigger('click');
      await flushPromises();

      expect(discoverSpy).toHaveBeenCalledWith(expect.objectContaining({ tag: undefined }));
      expect((wrapper.vm as any).selectedTag).toBeNull();
    });

    it('removes active skill chip when clicking its individual close button', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      (wrapper.vm as any).selectedSkill = 'get_weather';
      await wrapper.vm.$nextTick();

      const skillRemoveBtn = wrapper.find('.bg-blue-50 button');
      expect(skillRemoveBtn.exists()).toBe(true);

      discoverSpy.mockClear();
      await skillRemoveBtn.trigger('click');
      await flushPromises();

      expect((wrapper.vm as any).selectedSkill).toBeNull();
      expect(discoverSpy).toHaveBeenCalledWith(expect.objectContaining({ skill: undefined }));
    });

    it('renders fallback skill ID when active skill is not found in skills catalog', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      (wrapper.vm as any).selectedSkill = 'unlisted_skill_xyz';
      await wrapper.vm.$nextTick();

      expect(wrapper.text()).toContain('Skill: unlisted_skill_xyz');
    });

    it('formats singular "1 agent matches criteria" when exactly 1 agent is found', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      expect(wrapper.text()).toContain('1 agent matches criteria');
    });

    it('removes active tag chip when clicking its individual close button', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      (wrapper.vm as any).selectedTag = 'finance';
      await wrapper.vm.$nextTick();

      const tagRemoveBtn = wrapper.find('.bg-purple-50 button');
      expect(tagRemoveBtn.exists()).toBe(true);

      discoverSpy.mockClear();
      await tagRemoveBtn.trigger('click');
      await flushPromises();

      expect((wrapper.vm as any).selectedTag).toBeNull();
      expect(discoverSpy).toHaveBeenCalledWith(expect.objectContaining({ tag: undefined }));
    });

    it('clears all filters when clicking "Clear all filters" or empty state "Reset filters"', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      // Set state with multiple active filters
      (wrapper.vm as any).searchQuery = 'complex query';
      (wrapper.vm as any).selectedSkill = 'get_weather';
      (wrapper.vm as any).selectedTag = 'finance';
      await wrapper.vm.$nextTick();

      const clearAllBtn = wrapper.find('button.text-blue-600.hover\\:underline');
      expect(clearAllBtn.exists()).toBe(true);

      discoverSpy.mockClear();
      await clearAllBtn.trigger('click');
      await flushPromises();

      expect((wrapper.vm as any).searchQuery).toBe('');
      expect((wrapper.vm as any).selectedSkill).toBeNull();
      expect((wrapper.vm as any).selectedTag).toBeNull();
      expect(discoverSpy).toHaveBeenCalledWith({
        q: undefined,
        skill: undefined,
        tag: undefined,
      });
    });

    it('resets filters when clicking reset button in empty state card', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: [] } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      (wrapper.vm as any).searchQuery = 'unmatched term';
      await wrapper.vm.$nextTick();

      const resetBtn = wrapper.find('button.mt-4.px-4.py-2');
      expect(resetBtn.exists()).toBe(true);
      expect(resetBtn.text()).toBe('Reset filters');

      await resetBtn.trigger('click');
      await flushPromises();

      expect((wrapper.vm as any).searchQuery).toBe('');
    });
  });

  describe('Search Execution Error Handling', () => {
    it('sets error message when executeSearch API call fails with response message', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValueOnce({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      vi.spyOn(discoveryApi, 'discover').mockRejectedValueOnce({
        response: { data: { message: 'Query parsing failed' } },
      });

      await (wrapper.vm as any).executeSearch();
      await wrapper.vm.$nextTick();

      expect(wrapper.text()).toContain('Query parsing failed');
      expect((wrapper.vm as any).loading).toBe(false);
    });

    it('sets fallback error message when executeSearch API call fails without response data', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValueOnce({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      vi.spyOn(discoveryApi, 'discover').mockRejectedValueOnce(new Error('Internal server timeout'));

      await (wrapper.vm as any).executeSearch();
      await wrapper.vm.$nextTick();

      expect(wrapper.text()).toContain('Discovery search failed');
      expect((wrapper.vm as any).loading).toBe(false);
    });
  });

  describe('Semantic Vector Search Mode & Visual Similarity Scores', () => {
    it('toggles between keyword and semantic search mode and updates active UI controls', async () => {
      const discoverSpy = vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      const semanticSpy = vi.spyOn(discoveryApi, 'discoverSemantic').mockResolvedValue({ data: [mockAgent2] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      // Initial state: keyword mode
      expect((wrapper.vm as any).searchMode).toBe('keyword');
      expect(wrapper.text()).toContain('Skills Catalog');
      expect(wrapper.text()).toContain('Tags');
      expect(wrapper.text()).not.toContain('AI vector search ranks registered agents');

      // Toggle to AI Semantic Search
      const buttons = wrapper.findAll('div[role="group"][aria-label="Search mode"] button');
      const semanticBtn = buttons[1];
      expect(semanticBtn.text()).toContain('AI Semantic Search');

      semanticSpy.mockClear();
      await semanticBtn.trigger('click');
      await flushPromises();

      expect((wrapper.vm as any).searchMode).toBe('semantic');
      expect(semanticSpy).toHaveBeenCalledTimes(1);
      expect(wrapper.text()).toContain('AI vector search ranks registered agents');
      expect(wrapper.text()).toContain('Semantic Vector Results');
      // Skills and tag badges should be bypassed / hidden
      expect(wrapper.text()).not.toContain('Skills Catalog');
      expect(wrapper.findAllComponents({ name: 'SkillBadge' })).toHaveLength(0);

      // Toggle back to Keyword Search
      const keywordBtn = buttons[0];
      discoverSpy.mockClear();
      await keywordBtn.trigger('click');
      await flushPromises();

      expect((wrapper.vm as any).searchMode).toBe('keyword');
      expect(discoverSpy).toHaveBeenCalledTimes(1);
      expect(wrapper.text()).toContain('Skills Catalog');
      expect(wrapper.findAllComponents({ name: 'SkillBadge' }).length).toBeGreaterThan(0);
    });

    it('debounces user typing in semantic mode by 300ms and calls discoveryApi.discoverSemantic', async () => {
      vi.useFakeTimers();
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      const semanticSpy = vi.spyOn(discoveryApi, 'discoverSemantic').mockResolvedValue({ data: [mockAgent2] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      // Switch to semantic mode
      await (wrapper.vm as any).setSearchMode('semantic');
      await flushPromises();
      semanticSpy.mockClear();

      const searchInput = wrapper.find('input[type="text"]');
      await searchInput.setValue('portfolio analysis and risk');

      // Before 300ms, discoverSemantic is not called
      expect(semanticSpy).not.toHaveBeenCalled();

      vi.advanceTimersByTime(200);
      expect(semanticSpy).not.toHaveBeenCalled();

      // Complete debounce timer
      vi.advanceTimersByTime(100);
      await flushPromises();

      expect(semanticSpy).toHaveBeenCalledTimes(1);
      expect(semanticSpy).toHaveBeenCalledWith('portfolio analysis and risk');

      vi.useRealTimers();
    });

    it('assigns descending graduated similarity scores to results and renders match badges on cards', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'discoverSemantic').mockResolvedValue({ data: [mockAgent1, mockAgent2] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: [] } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      // Switch to semantic mode
      await (wrapper.vm as any).setSearchMode('semantic');
      await flushPromises();

      // Check agent models
      const agents = (wrapper.vm as any).agents;
      expect(agents).toHaveLength(2);
      expect(agents[0].similarityScore).toBe(0.98);
      expect(agents[1].similarityScore).toBe(0.94);

      // Verify AgentCard components receive similarityScore prop
      const agentCards = wrapper.findAllComponents({ name: 'AgentCard' });
      expect(agentCards).toHaveLength(2);
      expect(agentCards[0].props('similarityScore')).toBe(0.98);
      expect(agentCards[1].props('similarityScore')).toBe(0.94);

      // Verify badges rendered in card text
      expect(wrapper.text()).toContain('98% Match');
      expect(wrapper.text()).toContain('94% Match');
    });

    it('removes visual similarity scores when switching back to keyword mode', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1, mockAgent2] } as any);
      vi.spyOn(discoveryApi, 'discoverSemantic').mockResolvedValue({ data: [mockAgent1, mockAgent2] } as any);
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: mockSkills } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: mockTags } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      // Switch to semantic
      await (wrapper.vm as any).setSearchMode('semantic');
      await flushPromises();
      expect(wrapper.text()).toContain('98% Match');

      // Switch back to keyword
      await (wrapper.vm as any).setSearchMode('keyword');
      await flushPromises();

      const agentCards = wrapper.findAllComponents({ name: 'AgentCard' });
      expect(agentCards[0].props('similarityScore')).toBeUndefined();
      expect(wrapper.text()).not.toContain('Match');
    });

    it('handles semantic search errors and sets error message', async () => {
      vi.spyOn(discoveryApi, 'discover').mockResolvedValue({ data: [mockAgent1] } as any);
      vi.spyOn(discoveryApi, 'discoverSemantic').mockRejectedValue({
        response: { data: { message: 'Semantic vector index offline' } },
      });
      vi.spyOn(discoveryApi, 'getSkills').mockResolvedValue({ data: [] } as any);
      vi.spyOn(discoveryApi, 'getTags').mockResolvedValue({ data: [] } as any);

      const wrapper = mount(DiscoverView);
      await flushPromises();

      await (wrapper.vm as any).setSearchMode('semantic');
      await flushPromises();

      expect(wrapper.text()).toContain('Semantic vector index offline');
      expect((wrapper.vm as any).loading).toBe(false);
    });
  });
});
