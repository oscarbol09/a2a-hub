import { mount } from '@vue/test-utils';
import { describe, expect, it, vi } from 'vitest';
import appHtml from '../../../index.html?raw';
import NotFoundView from '../NotFoundView.vue';

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: vi.fn() }),
}));

describe('NotFoundView', () => {
  it('uses readable text styles inside the light app shell', () => {
    const wrapper = mount(NotFoundView);

    expect(wrapper.get('h1').classes()).toContain('text-gray-900');
    expect(wrapper.get('p').classes()).toContain('text-gray-600');
    expect(wrapper.find('[class*="dark:"]').exists()).toBe(false);
  });

  it('uses the product name in the browser tab title', () => {
    const document = new DOMParser().parseFromString(appHtml, 'text/html');

    expect(document.title).toBe('A2A Hub — Agent Discovery & Orchestration');
  });
});
