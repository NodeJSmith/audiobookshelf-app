// @vitest-environment jsdom
// Proves the runner can mount Vue 2 single-file components.
import { describe, expect, it } from 'vitest'
import { mount } from '@vue/test-utils'
import ToggleSwitch from '@/components/ui/ToggleSwitch.vue'

describe('ToggleSwitch', () => {
  it('emits the flipped value when clicked', async () => {
    const wrapper = mount(ToggleSwitch, { propsData: { value: false } })
    await wrapper.find('.cursor-pointer').trigger('click')
    expect(wrapper.emitted('input')).toEqual([[true]])
  })

  it('ignores clicks while disabled', async () => {
    const wrapper = mount(ToggleSwitch, { propsData: { value: false, disabled: true } })
    await wrapper.find('.cursor-pointer').trigger('click')
    expect(wrapper.emitted('input')).toBeUndefined()
  })
})
