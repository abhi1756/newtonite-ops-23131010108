import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { api, ApiError, isNetworkError, newIdempotencyKey } from './client';

// Polling instead of websockets: cheap, and stale writes are still caught by version checks on the server.
const ITEM_POLL_MS = 15_000;
const LIST_POLL_MS = 30_000;

export const keys = {
  me: ['me'],
  summary: ['summary'],
  items: (filter) => ['items', filter],
  item: (id) => ['item', id],
  activity: (id) => ['item', id, 'activity'],
  comments: (id) => ['item', id, 'comments'],
  members: (teamId) => ['team', teamId, 'members'],
  notifications: ['notifications'],
};

export function useMe(enabled = true) {
  return useQuery({ queryKey: keys.me, queryFn: () => api('/me'), enabled, staleTime: 60_000 });
}

export function useSummary() {
  return useQuery({
    queryKey: keys.summary,
    queryFn: () => api('/items/summary'),
    refetchInterval: LIST_POLL_MS,
  });
}

function toQuery(filter, cursor, limit = 25) {
  const params = new URLSearchParams();
  Object.entries(filter).forEach(([k, v]) => {
    if (v) params.set(k, String(v));
  });
  if (cursor) params.set('cursor', cursor);
  params.set('limit', String(limit));
  return params.toString();
}

/** Server-side filtered, cursor-paginated list. Only the pages the user asked for are ever loaded. */
export function useItems(filter, limit = 25) {
  return useInfiniteQuery({
    queryKey: [...keys.items(filter), limit],
    queryFn: ({ pageParam, signal }) => api(`/items?${toQuery(filter, pageParam, limit)}`, { signal }),
    initialPageParam: undefined,
    getNextPageParam: (last) => last.nextCursor,
    placeholderData: keepPreviousData,
    refetchInterval: LIST_POLL_MS,
  });
}

export function useItem(id) {
  return useQuery({
    queryKey: keys.item(id),
    queryFn: ({ signal }) => api(`/items/${id}`, { signal }),
    refetchInterval: ITEM_POLL_MS,
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  });
}

export function useActivity(id) {
  return useInfiniteQuery({
    queryKey: keys.activity(id),
    queryFn: ({ pageParam }) => api(`/items/${id}/activity?limit=30${pageParam ? `&cursor=${pageParam}` : ''}`),
    initialPageParam: undefined,
    getNextPageParam: (last) => last.nextCursor,
    refetchInterval: ITEM_POLL_MS,
  });
}

export function useComments(id) {
  return useInfiniteQuery({
    queryKey: keys.comments(id),
    queryFn: ({ pageParam }) => api(`/items/${id}/comments?limit=30${pageParam ? `&cursor=${pageParam}` : ''}`),
    initialPageParam: undefined,
    getNextPageParam: (last) => last.nextCursor,
    refetchInterval: ITEM_POLL_MS,
  });
}

export function useTeamMembers(teamId, enabled) {
  return useQuery({
    queryKey: keys.members(teamId),
    queryFn: () => api(`/teams/${teamId}/members`),
    enabled,
    staleTime: 60_000,
  });
}

export function useNotifications() {
  return useQuery({
    queryKey: keys.notifications,
    queryFn: () => api('/notifications?limit=20'),
    refetchInterval: 20_000,
  });
}

/**
 * Mutation of one work item with optimistic UI.
 *
 * - The idempotency key is created once per user action and travels with the variables, so the
 *   automatic retries after a network failure reuse it and the server never applies the action twice.
 * - `optimistic(item, vars)` updates the cached item at once; on any error the previous value is restored.
 * - On 409 STALE_VERSION the server sends the current item, which replaces the cache so the user
 *   sees what changed.
 *
 * @param {number} id
 * @param {(vars: any, idempotencyKey: string) => Promise<import('./model').WorkItemDetail>} request
 * @param {(item: import('./model').WorkItemDetail, vars: any) => import('./model').WorkItemDetail} [optimistic]
 */
export function useItemMutation(id, request, optimistic) {
  const qc = useQueryClient();
  const mutation = useMutation({
    mutationFn: ({ vars, key }) => request(vars, key),
    retry: (count, error) => isNetworkError(error) && count < 2,
    onMutate: async ({ vars }) => {
      await qc.cancelQueries({ queryKey: keys.item(id), exact: true });
      const previous = qc.getQueryData(keys.item(id));
      if (previous && optimistic) {
        qc.setQueryData(keys.item(id), optimistic(previous, vars));
      }
      return { previous };
    },
    onError: (error, _vars, context) => {
      const current = error instanceof ApiError ? error.body?.current : undefined;
      if (current) {
        qc.setQueryData(keys.item(id), current);
      } else if (context?.previous) {
        qc.setQueryData(keys.item(id), context.previous);
      }
    },
    onSuccess: (item) => {
      qc.setQueryData(keys.item(id), item);
    },
    onSettled: () => {
      qc.invalidateQueries({ queryKey: keys.activity(id) });
      qc.invalidateQueries({ queryKey: ['items'] });
      qc.invalidateQueries({ queryKey: keys.summary });
    },
  });
  return {
    ...mutation,
    run: (vars) => mutation.mutate({ vars, key: newIdempotencyKey() }),
  };
}
