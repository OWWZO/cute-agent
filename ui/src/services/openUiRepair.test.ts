import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { repairOpenUiProgram } from './openUiRepair';

describe('repairOpenUiProgram', () => {
  const fetchMock = vi.fn();

  beforeEach(() => {
    vi.stubGlobal('fetch', fetchMock);
    fetchMock.mockReset();
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    vi.useRealTimers();
  });

  it('posts the program to the repair endpoint and returns the fix', async () => {
    fetchMock.mockResolvedValue({
      ok: true,
      json: async () => ({ status: 'fixed', content: 'root = Col([])', errors: [] }),
    });

    const result = await repairOpenUiProgram('root = Col([a])\na = Nope("x")');

    expect(result.status).toBe('fixed');
    expect(result.content).toBe('root = Col([])');

    const [url, init] = fetchMock.mock.calls[0];
    expect(String(url)).toContain('/api/agent/openui/repair');
    expect(init.method).toBe('POST');
    expect(JSON.parse(init.body)).toEqual({
      program: 'root = Col([a])\na = Nope("x")',
      context: [],
    });
  });

  it('degrades to error status on a non-2xx response', async () => {
    fetchMock.mockResolvedValue({ ok: false, status: 502, json: async () => ({}) });

    const result = await repairOpenUiProgram('root = Col([])');

    expect(result.status).toBe('error');
    expect(result.message).toContain('502');
  });

  it('degrades instead of throwing when the network fails', async () => {
    fetchMock.mockRejectedValue(new Error('offline'));

    const result = await repairOpenUiProgram('root = Col([])');

    expect(result.status).toBe('error');
    expect(result.message).toContain('offline');
  });

  it('stops waiting and reports a timeout when the repair request hangs', async () => {
    vi.useFakeTimers();
    fetchMock.mockImplementation(
      (_url: string, init: RequestInit) =>
        new Promise((_resolve, reject) => {
          init.signal?.addEventListener('abort', () => {
            reject(new DOMException('Aborted', 'AbortError'));
          });
        })
    );

    const pending = repairOpenUiProgram('root = Col([])');
    await vi.advanceTimersByTimeAsync(95_000);
    const result = await pending;

    expect(result).toEqual({ status: 'error', message: '修复请求超时' });
  });
});
