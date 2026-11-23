import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { afterEach, expect, it, vi } from 'vitest';
import { api, type Agent } from '../../services/api';
import { wsService } from '../../services/websocket';
import AgentRegistryView from '../AgentRegistryView.vue';

afterEach(() => {
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

it.each([false, true])('waits for the user to confirm unregistration (%s)', async (confirmed) => {
  const agent: Agent = {
    id: 'weather-1',
    name: 'Weather Agent',
    description: 'Weather reports',
    status: 'HEALTHY',
    url: 'https://weather.example.com',
    version: '1.0',
    providerName: null,
    authType: 'NONE',
    lastSeenAt: null,
    registeredAt: '2026-11-01T00:00:00Z',
  };
  vi.spyOn(api, 'get').mockImplementation(async (url) => ({
    data: url === '/agents' ? [agent] : {
      totalAgents: 1, healthyAgents: 1, degradedAgents: 0, offlineAgents: 0,
      unknownAgents: 0, averageLatencyMs: 0,
    },
  }));
  vi.spyOn(wsService, 'connect').mockImplementation(() => {});
  const confirm = vi.fn(() => confirmed);
  vi.stubGlobal('confirm', confirm);
  const deleteAgent = vi.spyOn(api, 'delete').mockResolvedValue({});
  const wrapper = mount(AgentRegistryView, { global: { plugins: [createPinia()] } });

  try {
    await flushPromises();
    await wrapper.get('button[aria-label="Unregister Weather Agent"]').trigger('click');
    await flushPromises();

    expect(confirm).toHaveBeenCalledOnce();
    expect(confirm).toHaveBeenCalledWith(expect.stringContaining('Weather Agent'));
    if (confirmed) {
      expect(deleteAgent).toHaveBeenCalledExactlyOnceWith('/agents/weather-1');
      expect(wrapper.text()).not.toContain('Weather Agent');
    } else {
      expect(deleteAgent).not.toHaveBeenCalled();
      expect(wrapper.text()).toContain('Weather Agent');
    }
  } finally {
    wrapper.unmount();
  }
});
