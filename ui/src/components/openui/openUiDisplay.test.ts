import { describe, expect, it } from 'vitest';

import { resolveOpenUiDisplay, selectRepairForSource } from './openUiDisplay';

describe('selectRepairForSource', () => {
  it('源码变化后立即丢弃旧修复结果', () => {
    expect(
      selectRepairForSource('new source', {
        source: 'old source',
        program: 'root = Col([old])'
      })
    ).toBeNull();
  });

  it('源码一致时采用对应修复结果', () => {
    expect(
      selectRepairForSource('same source', {
        source: 'same source',
        program: 'root = Col([fixed])'
      })
    ).toBe('root = Col([fixed])');
  });
});

describe('resolveOpenUiDisplay', () => {
  it('最终校验通过时使用最终程序', () => {
    expect(
      resolveOpenUiDisplay({
        program: 'root = Col([])',
        repaired: null,
        streaming: false,
        invalid: false,
        lastRendered: 'root = Col([old])'
      })
    ).toEqual({
      text: 'root = Col([])',
      streaming: false,
      frozen: false
    });
  });

  it('最终校验失败时保留最后一帧，不切到原始代码', () => {
    expect(
      resolveOpenUiDisplay({
        program: 'root = Col([missing])',
        repaired: null,
        streaming: false,
        invalid: true,
        lastRendered: 'root = Col([title])\ntitle = Title("Q3")'
      })
    ).toEqual({
      text: 'root = Col([title])\ntitle = Title("Q3")',
      streaming: true,
      frozen: true
    });
  });

  it('修复结果通过验收后优先显示修复版', () => {
    expect(
      resolveOpenUiDisplay({
        program: 'root = Col([missing])',
        repaired: 'root = Col([title])\ntitle = Title("Q3")',
        streaming: false,
        invalid: false,
        lastRendered: 'root = Col([title])'
      })
    ).toEqual({
      text: 'root = Col([title])\ntitle = Title("Q3")',
      streaming: false,
      frozen: false
    });
  });

  it('首次打开就是坏程序时没有可冻结画面', () => {
    expect(
      resolveOpenUiDisplay({
        program: 'root = Col([missing])',
        repaired: null,
        streaming: false,
        invalid: true,
        lastRendered: null
      })
    ).toBeNull();
  });
});
