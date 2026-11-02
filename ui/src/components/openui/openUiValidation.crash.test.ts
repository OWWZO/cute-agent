import { describe, expect, it, vi } from 'vitest';

// 让 parser 在加载期就抛异常，模拟 schema 不匹配 / 内部 bug 而不是输入截断。
vi.mock('@openuidev/lang-core', () => ({
  createParser: () => ({
    parse: () => {
      throw new Error('schema mismatch');
    }
  })
}));

import { diagnoseProgram } from './openUiValidation';

describe('diagnoseProgram parser crash', () => {
  it('把 parser 异常写进 errors，而不是退化成无信息的 incomplete', () => {
    const consoleError = vi.spyOn(console, 'error').mockImplementation(() => undefined);
    try {
      const diagnosis = diagnoseProgram('root = Col([])');

      expect(diagnosis.renderable).toBe(false);
      expect(diagnosis.errors).toEqual([
        {
          code: 'parser-crash',
          message: 'schema mismatch'
        }
      ]);
      expect(consoleError).toHaveBeenCalled();
    } finally {
      consoleError.mockRestore();
    }
  });
});
