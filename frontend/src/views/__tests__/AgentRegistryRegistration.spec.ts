import { flushPromises, mount } from '@vue/test-utils';
import { createPinia } from 'pinia';
import { afterEach, expect, it, vi } from 'vitest';
import { api } from '../../services/api';
import { wsService } from '../../services/websocket';
import AgentRegistryView from '../AgentRegistryView.vue';

afterEach(() => vi.restoreAllMocks());

it('shows a failed registration in the dialog and clears it on reopen', async () => {
  vi.spyOn(api, 'get').mockImplementation(async (url) => ({
    data: url === '/agents' ? [] : {
      totalAgents: 0, healthyAgents: 0, degradedAgents: 0, offlineAgents: 0,
      unknownAgents: 0, averageLatencyMs: 0,
    },
  }));
  vi.spyOn(wsService, 'connect').mockImplementation(() => {});
  vi.spyOn(api, 'post').mockRejectedValueOnce({
    response: { data: { message: 'Agent URL already registered' } },
  });
  const wrapper = mount(AgentRegistryView, { global: { plugins: [createPinia()] } });

  try {
    await flushPromises();
    await wrapper.get('button').trigger('click');
    await wrapper.get('#agent-url-input').setValue('https://agent.example.com');
    await wrapper.get('#agent-url-input').trigger('keyup.enter');
    await flushPromises();

    const dialog = wrapper.get('[role="dialog"]');
    expect(dialog.get('[role="alert"]').text()).toBe('Agent URL already registered');
    expect(dialog.findAll('button')).toHaveLength(2);
    await dialog.get('button').trigger('click');
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false);

    await wrapper.get('button').trigger('click');
    expect(wrapper.get('[role="dialog"]').find('[role="alert"]').exists()).toBe(false);
  } finally {
    wrapper.unmount();
  }
});
