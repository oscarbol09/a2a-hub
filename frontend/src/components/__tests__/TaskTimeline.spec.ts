import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import TaskTimeline from '../TaskTimeline.vue';
import type { TaskDto } from '../../services/api';

describe('TaskTimeline Component', () => {
  const baseTask: TaskDto = {
    id: 'task-test-001',
    agentId: 'agent-weather-1',
    agentName: 'Weather Agent',
    contextId: 'ctx-100',
    state: 'SUBMITTED',
    request: { city: 'Tokyo', units: 'metric' },
    response: null,
    errorDetail: '',
    createdAt: '2026-10-11T12:00:00Z',
    updatedAt: '2026-10-11T12:00:00Z',
  };

  it('renders nothing (<!--v-if-->) when task prop is null', () => {
    const wrapper = mount(TaskTimeline, {
      props: {
        task: null,
      },
    });

    expect(wrapper.html()).toBe('<!--v-if-->');
    expect(wrapper.find('.bg-white').exists()).toBe(false);
  });

  describe('Active states', () => {
    it.each(['SUBMITTED', 'WORKING'])('renders loader, %s text, and request payload JSON', (state) => {
      const task: TaskDto = {
        ...baseTask,
        state,
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('Task Execution');
      expect(wrapper.text()).toContain('ID: task-test-001');
      expect(wrapper.text()).toContain(state);

      // State badge styling and spinner loader
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.exists()).toBe(true);
      expect(badge.classes()).toContain('bg-blue-50');
      expect(badge.classes()).toContain('text-blue-700');

      const spinnerIcon = badge.find('.animate-spin');
      expect(spinnerIcon.exists()).toBe(true);
      expect(spinnerIcon.classes()).toContain('text-blue-500');

      // Request payload rendered in pre tag
      const payloadPre = wrapper.find('.bg-gray-50 pre');
      expect(payloadPre.exists()).toBe(true);
      expect(payloadPre.text()).toContain('"city": "Tokyo"');
      expect(payloadPre.text()).toContain('"units": "metric"');

      // Response and Error sections should not exist
      expect(wrapper.find('.bg-emerald-50').exists()).toBe(false);
      expect(wrapper.find('.bg-red-50').exists()).toBe(false);
    });
  });

  describe('COMPLETED State', () => {
    it('renders success checkmark, COMPLETED text, and response JSON', () => {
      const task: TaskDto = {
        ...baseTask,
        state: 'COMPLETED',
        response: { temp: 21.5, condition: 'Sunny' },
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('COMPLETED');

      // State badge styling
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.exists()).toBe(true);
      expect(badge.classes()).toContain('bg-emerald-50');
      expect(badge.classes()).toContain('text-emerald-700');

      // Success checkmark icon (not spinning)
      const icon = badge.find('svg');
      expect(icon.exists()).toBe(true);
      expect(icon.classes()).toContain('text-emerald-500');
      expect(icon.classes()).not.toContain('animate-spin');

      // Response section rendered with emerald styling
      const responseSection = wrapper.find('.bg-emerald-50.rounded-lg');
      expect(responseSection.exists()).toBe(true);
      expect(wrapper.text()).toContain('Response');
      const responsePre = responseSection.find('pre');
      expect(responsePre.text()).toContain('"temp": 21.5');
      expect(responsePre.text()).toContain('"condition": "Sunny"');

      // Error section should not exist
      expect(wrapper.find('.bg-red-50.rounded-lg').exists()).toBe(false);
    });

    it('renders success styling for SUCCESS alias state', () => {
      const task: TaskDto = {
        ...baseTask,
        state: 'SUCCESS',
        response: { ok: true },
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('SUCCESS');
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.classes()).toContain('bg-emerald-50');
    });
  });

  describe('FAILED State', () => {
    it('renders red error state, FAILED text, and error detail', () => {
      const task: TaskDto = {
        ...baseTask,
        state: 'FAILED',
        errorDetail: 'Downstream agent timed out after 30000ms',
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('FAILED');

      // State badge styling
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.exists()).toBe(true);
      expect(badge.classes()).toContain('bg-red-50');
      expect(badge.classes()).toContain('text-red-700');

      const icon = badge.find('svg');
      expect(icon.exists()).toBe(true);
      expect(icon.classes()).toContain('text-red-500');

      // Error details section
      const errorSection = wrapper.find('.bg-red-50.rounded-lg');
      expect(errorSection.exists()).toBe(true);
      expect(wrapper.text()).toContain('Error Details');
      const errorPre = errorSection.find('pre');
      expect(errorPre.text()).toBe('Downstream agent timed out after 30000ms');

      // Response section should not exist
      expect(wrapper.find('.bg-emerald-50').exists()).toBe(false);
    });

    it('renders error styling for ERROR alias state', () => {
      const task: TaskDto = {
        ...baseTask,
        state: 'ERROR',
        errorDetail: 'Internal server error 500',
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('ERROR');
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.classes()).toContain('bg-red-50');
    });
  });

  describe('Other / Intermediate States', () => {
    it('renders spinner for RUNNING state', () => {
      const task: TaskDto = {
        ...baseTask,
        state: 'RUNNING',
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('RUNNING');
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.classes()).toContain('bg-blue-50');
      expect(badge.find('.animate-spin').exists()).toBe(true);
    });

    it('renders spinner for PENDING state', () => {
      const task: TaskDto = {
        ...baseTask,
        state: 'PENDING',
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('PENDING');
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.classes()).toContain('bg-blue-50');
      expect(badge.find('.animate-spin').exists()).toBe(true);
    });

    it('renders fallback clock icon and neutral gray badge for unknown state', () => {
      const task: TaskDto = {
        ...baseTask,
        state: 'UNKNOWN_CUSTOM_STATE',
      };

      const wrapper = mount(TaskTimeline, {
        props: { task },
      });

      expect(wrapper.text()).toContain('UNKNOWN_CUSTOM_STATE');
      const badge = wrapper.find('.rounded-full.border');
      expect(badge.classes()).toContain('bg-gray-50');
      expect(badge.classes()).toContain('text-gray-700');
    });

    it('reacts dynamically to task prop updates', async () => {
      const wrapper = mount(TaskTimeline, {
        props: {
          task: { ...baseTask, state: 'SUBMITTED' },
        },
      });

      expect(wrapper.text()).toContain('SUBMITTED');
      expect(wrapper.find('.bg-emerald-50').exists()).toBe(false);

      await wrapper.setProps({
        task: {
          ...baseTask,
          state: 'COMPLETED',
          response: { finished: true },
        },
      });

      expect(wrapper.text()).toContain('COMPLETED');
      expect(wrapper.find('.bg-emerald-50').exists()).toBe(true);
    });
  });
});
