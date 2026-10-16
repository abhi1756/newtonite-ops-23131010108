import { afterEach, describe, expect, it, vi } from 'vitest';
import { act, renderHook, waitFor } from '@testing-library/react';
import { QueryClient, QueryClientProvider } from '@tanstack/react-query';
import type { ReactNode } from 'react';
import { api } from './client';
import { keys, useItemMutation } from './hooks';
import type { WorkItemDetail } from './types';

const item: WorkItemDetail = {
  id: 1,
  teamId: 1,
  teamName: 'Payments',
  title: 'Refund',
  description: '',
  type: 'PAYMENT',
  priority: 'HIGH',
  status: 'OPEN',
  overdue: false,
  version: 3,
  createdAt: '2026-10-16T10:00:00Z',
  updatedAt: '2026-10-16T10:00:00Z',
  createdBy: { id: 2, name: 'Bob' },
  permissions: { canEdit: true, canClaim: true, canAssign: false, canComment: true, transitions: [] },
};

function jsonResponse(status: number, body: unknown) {
  return new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } });
}

function setup() {
  const qc = new QueryClient({ defaultOptions: { queries: { retry: false } } });
  qc.setQueryData(keys.item(1), item);
  const wrapper = ({ children }: { children: ReactNode }) => <QueryClientProvider client={qc}>{children}</QueryClientProvider>;
  const { result } = renderHook(
    () =>
      useItemMutation<{ priority: 'URGENT' }>(
        1,
        (v, key) => api(`/items/1`, { method: 'PATCH', body: { ...v, version: 3 }, idempotencyKey: key }),
        (current, v) => ({ ...current, priority: v.priority }),
      ),
    { wrapper },
  );
  return { qc, result };
}

describe('useItemMutation', () => {
  afterEach(() => vi.unstubAllGlobals());

  it('shows the change immediately and rolls it back when the server refuses', async () => {
    let respond!: (r: Response) => void;
    vi.stubGlobal('fetch', vi.fn(() => new Promise<Response>((resolve) => (respond = resolve))));
    const { qc, result } = setup();

    act(() => result.current.run({ priority: 'URGENT' }));
    await waitFor(() => expect(qc.getQueryData<WorkItemDetail>(keys.item(1))?.priority).toBe('URGENT'));

    respond(jsonResponse(403, { code: 'FORBIDDEN', detail: 'Only the assignee can edit this item' }));

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(qc.getQueryData<WorkItemDetail>(keys.item(1))?.priority).toBe('HIGH');
    expect(result.current.error?.message).toBe('Only the assignee can edit this item');
  });

  it('replaces the cache with the server copy on a version conflict', async () => {
    const latest = { ...item, title: 'Refund (edited by Alice)', version: 4 };
    vi.stubGlobal('fetch', vi.fn(async () => jsonResponse(409, { code: 'STALE_VERSION', detail: 'changed', current: latest })));
    const { qc, result } = setup();

    act(() => result.current.run({ priority: 'URGENT' }));

    await waitFor(() => expect(result.current.isError).toBe(true));
    expect(qc.getQueryData<WorkItemDetail>(keys.item(1))).toEqual(latest);
  });

  it('retries a network failure with the same idempotency key', async () => {
    const fetchMock = vi
      .fn()
      .mockRejectedValueOnce(new TypeError('Failed to fetch'))
      .mockResolvedValueOnce(jsonResponse(200, { ...item, priority: 'URGENT', version: 4 }));
    vi.stubGlobal('fetch', fetchMock);
    const { result } = setup();

    act(() => result.current.run({ priority: 'URGENT' }));

    await waitFor(() => expect(result.current.isSuccess).toBe(true), { timeout: 4000 });
    expect(fetchMock).toHaveBeenCalledTimes(2);
    const keyOf = (call: number) => (fetchMock.mock.calls[call][1] as RequestInit).headers as Record<string, string>;
    expect(keyOf(0)['Idempotency-Key']).toBeTruthy();
    expect(keyOf(1)['Idempotency-Key']).toBe(keyOf(0)['Idempotency-Key']);
  });
});
