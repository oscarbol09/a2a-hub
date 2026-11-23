import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { mount, flushPromises } from '@vue/test-utils';
import { setActivePinia, createPinia } from 'pinia';
import OrchestrateView from '../OrchestrateView.vue';
import TaskTimeline from '../../components/TaskTimeline.vue';
import { agentsApi, orchestrationApi, tasksApi, type Agent, type TaskDto } from '../../services/api';
import { wsService } from '../../services/websocket';
import { useTaskStore } from '../../stores/taskStore';

const mockAgent1: Agent = {
  id: 'agent-1',
  name: 'DataAnalyzerAgent',
  description: 'Analyzes structured datasets',
  url: 'https://data.agent.io',
  version: '1.0.0',
  providerName: null,
  status: 'HEALTHY',
  authType: 'NONE',
  registeredAt: '2026-10-01T10:00:00Z',
  lastSeenAt: '2026-10-01T10:30:00Z',
};

const mockAgent2: Agent = {
  id: 'agent-2',
  name: 'TranslatorAgent',
  description: 'Translates languages',
  url: 'https://translate.agent.io',
  version: '1.2.0',
  providerName: 'DeepL',
  status: 'DEGRADED',
  authType: 'API_KEY',
  registeredAt: '2026-10-01T11:00:00Z',
  lastSeenAt: '2026-10-01T11:00:00Z',
};

const mockOfflineAgent: Agent = {
  id: 'agent-offline',
  name: 'OfflineAgent',
  description: 'Inactive node',
  url: 'https://offline.agent.io',
  version: '0.1.0',
  providerName: null,
  status: 'OFFLINE',
  authType: 'NONE',
  registeredAt: '2026-10-01T08:00:00Z',
  lastSeenAt: null,
};

const mockTaskResult: TaskDto = {
  id: 'task-orch-001',
  agentId: 'agent-1',
  agentName: 'DataAnalyzerAgent',
  contextId: 'ctx-orch-01',
  state: 'SUBMITTED',
  request: { query: 'Hello Agent' },
  response: null,
  errorDetail: '',
  createdAt: '2026-10-11T12:00:00Z',
  updatedAt: '2026-10-11T12:00:00Z',
};

describe('OrchestrateView Component', () => {
  let pinia: ReturnType<typeof createPinia>;

  beforeEach(() => {
    pinia = createPinia();
    setActivePinia(pinia);
    vi.restoreAllMocks();
    vi.spyOn(wsService, 'connect').mockImplementation(() => {});
  });

  afterEach(() => {
    vi.restoreAllMocks();
  });

  describe('Agent Loading & Dropdown', () => {
    it('loads active agents and populates agent select dropdown while filtering out offline agents', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1, mockAgent2, mockOfflineAgent],
      } as any);

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      expect(agentsApi.getAll).toHaveBeenCalledTimes(1);
      expect(wsService.connect).toHaveBeenCalledTimes(1);

      // Verify dropdown select options
      const select = wrapper.find('select');
      expect(select.exists()).toBe(true);

      const options = select.findAll('option');
      // 1 disabled placeholder + 2 active agents (offline excluded) = 3 options
      expect(options).toHaveLength(3);

      expect(wrapper.text()).toContain('DataAnalyzerAgent (HEALTHY)');
      expect(wrapper.text()).toContain('TranslatorAgent (DEGRADED)');
      expect(wrapper.text()).not.toContain('OfflineAgent');

      // First active agent should be preselected automatically
      expect((select.element as HTMLSelectElement).value).toBe('agent-1');
      expect((wrapper.vm as any).selectedAgentId).toBe('agent-1');
    });

    it('renders error message when loading active agents fails', async () => {
      vi.spyOn(agentsApi, 'getAll').mockRejectedValueOnce(new Error('Gateway timeout'));

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      expect(wrapper.text()).toContain('Failed to load active agents. Gateway timeout');
    });
  });

  describe('AI Agent Suggestion', () => {
    it('requests AI suggestion and sets selectedAgentId, reasoning, and Gemini badge when source is LLM_REASONING', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1, mockAgent2],
      } as any);

      vi.spyOn(orchestrationApi, 'suggest').mockResolvedValueOnce({
        data: {
          agentId: 'agent-2',
          agentName: 'TranslatorAgent',
          reasoning: 'TranslatorAgent is ideal for multilingual translation tasks.',
          source: 'LLM_REASONING',
          fallback: false,
          confidenceScore: 0.95,
        },
      } as any);

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      // Enter task description
      const textarea = wrapper.find('textarea[placeholder*="I need someone to"]');
      expect(textarea.exists()).toBe(true);
      await textarea.setValue('Translate this contract from Spanish to English');

      // Click "Get Suggestion"
      const suggestBtn = wrapper.find('button.bg-indigo-600');
      expect(suggestBtn.exists()).toBe(true);
      await suggestBtn.trigger('click');
      await flushPromises();

      expect(orchestrationApi.suggest).toHaveBeenCalledWith({
        taskDescription: 'Translate this contract from Spanish to English',
      });

      // Selected agent should change to agent-2
      expect((wrapper.vm as any).selectedAgentId).toBe('agent-2');

      // Reasoning box rendered
      expect(wrapper.text()).toContain('TranslatorAgent is ideal for multilingual translation tasks.');
      expect(wrapper.text()).toContain('AI Reasoning');
      expect(wrapper.text()).toContain('Gemini AI');
      expect(wrapper.text()).not.toContain('Vector Match (Fallback)');
    });

    it('renders fallback badge and styling when suggestion source is VECTOR_FALLBACK', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1, mockAgent2],
      } as any);

      vi.spyOn(orchestrationApi, 'suggest').mockResolvedValueOnce({
        data: {
          agentId: 'agent-1',
          agentName: 'DataAnalyzerAgent',
          reasoning: 'Vector semantic match found relevant data analysis capabilities.',
          source: 'VECTOR_FALLBACK',
          fallback: true,
          confidenceScore: 0.78,
          fallbackReason: 'LLM quota exceeded, fell back to vector embedding search',
        },
      } as any);

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      const textarea = wrapper.find('textarea[placeholder*="I need someone to"]');
      await textarea.setValue('Analyze sales CSV data');

      const suggestBtn = wrapper.find('button.bg-indigo-600');
      await suggestBtn.trigger('click');
      await flushPromises();

      expect((wrapper.vm as any).selectedAgentId).toBe('agent-1');

      // Fallback reasoning and badge
      expect(wrapper.text()).toContain('Fallback Reasoning');
      expect(wrapper.text()).toContain('Vector Match (Fallback)');
      expect(wrapper.text()).toContain('Vector semantic match found relevant data analysis capabilities.');

      const fallbackBadge = wrapper.find('.bg-amber-200.text-amber-800');
      expect(fallbackBadge.exists()).toBe(true);
    });

    it('displays error banner when suggestAgent fails', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1],
      } as any);

      vi.spyOn(orchestrationApi, 'suggest').mockRejectedValueOnce({
        response: { data: { detail: 'Rate limit exceeded on Gemini API' } },
      });

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      const textarea = wrapper.find('textarea[placeholder*="I need someone to"]');
      await textarea.setValue('Do something');

      const suggestBtn = wrapper.find('button.bg-indigo-600');
      await suggestBtn.trigger('click');
      await flushPromises();

      expect(wrapper.text()).toContain('Rate limit exceeded on Gemini API');
    });

    it('does not dispatch suggest call if task description is empty', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1],
      } as any);
      const suggestSpy = vi.spyOn(orchestrationApi, 'suggest');

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      await (wrapper.vm as any).suggestAgent();

      expect(suggestSpy).not.toHaveBeenCalled();
    });
  });

  describe('Task Submission & TaskTimeline Integration', () => {
    it('displays error message and does not submit task when payload contains invalid JSON', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1],
      } as any);

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      const taskStore = useTaskStore();
      const submitTaskSpy = vi.spyOn(taskStore, 'submitTask');

      // Set invalid JSON payload in textarea
      const payloadTextarea = wrapper.find('textarea.font-mono');
      expect(payloadTextarea.exists()).toBe(true);
      await payloadTextarea.setValue('{ invalid json: true, missing quotes }');

      // Submit form
      const form = wrapper.find('form');
      await form.trigger('submit');
      await flushPromises();

      expect(wrapper.text()).toContain('Invalid JSON in payload.');
      expect(submitTaskSpy).not.toHaveBeenCalled();
      expect(wrapper.findComponent(TaskTimeline).exists()).toBe(false);
    });

    it('displays error when attempting to submit with no agent selected', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [],
      } as any);

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      const form = wrapper.find('form');
      await form.trigger('submit');
      await flushPromises();

      expect(wrapper.text()).toContain('Please select an agent.');
    });

    it.each([true, false])('submits valid JSON with async mode %s and displays TaskTimeline', async (runAsynchronously) => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1],
      } as any);

      vi.spyOn(tasksApi, 'submitTask').mockResolvedValueOnce({
        status: runAsynchronously ? 202 : 201,
        data: mockTaskResult,
      } as any);
      vi.spyOn(tasksApi, 'getTask').mockResolvedValueOnce({ data: mockTaskResult } as any);

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      const taskStore = useTaskStore();
      const submitSpy = vi.spyOn(taskStore, 'submitTask');

      // Initial state: No active task placeholder visible
      expect(wrapper.text()).toContain('No active task');
      expect(wrapper.findComponent(TaskTimeline).exists()).toBe(false);

      // Set valid JSON payload
      const payloadTextarea = wrapper.find('textarea.font-mono');
      await payloadTextarea.setValue(JSON.stringify({ query: 'Execute analytics task' }));
      const asyncToggle = wrapper.get<HTMLInputElement>('#task-async');
      expect(asyncToggle.element.checked).toBe(true);
      if (!runAsynchronously) {
        await asyncToggle.setValue(false);
      }

      // Submit form
      const form = wrapper.find('form');
      await form.trigger('submit');
      await flushPromises();

      expect(submitSpy).toHaveBeenCalledWith('agent-1', {
        query: 'Execute analytics task',
      }, runAsynchronously);
      expect(tasksApi.submitTask).toHaveBeenCalledWith({
        agentId: 'agent-1',
        payload: { query: 'Execute analytics task' },
        async: runAsynchronously,
      });

      // TaskTimeline component should now be rendered with active task
      const timeline = wrapper.findComponent(TaskTimeline);
      expect(timeline.exists()).toBe(true);
      expect(timeline.props('task')).toEqual(mockTaskResult);
      expect(wrapper.text()).not.toContain('No active task');
    });

    it('displays error message when taskStore.submitTask fails', async () => {
      vi.spyOn(agentsApi, 'getAll').mockResolvedValueOnce({
        data: [mockAgent1],
      } as any);

      vi.spyOn(tasksApi, 'submitTask').mockRejectedValueOnce(
        new Error('Agent endpoint unreachable')
      );

      const wrapper = mount(OrchestrateView, {
        global: { plugins: [pinia] },
      });
      await flushPromises();

      const payloadTextarea = wrapper.find('textarea.font-mono');
      await payloadTextarea.setValue('{"action": "ping"}');

      const form = wrapper.find('form');
      await form.trigger('submit');
      await flushPromises();

      expect(wrapper.text()).toContain('Agent endpoint unreachable');
    });
  });
});
