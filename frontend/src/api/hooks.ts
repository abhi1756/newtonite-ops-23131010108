import {
  keepPreviousData,
  useInfiniteQuery,
  useMutation,
  useQuery,
  useQueryClient,
} from '@tanstack/react-query';
import { api, ApiError, isNetworkError, newIdempotencyKey } from './client';
import type {
  ActivityEntry,
  CommentEntry,
  DashboardSummary,
  ItemFilter,
  Me,
  NotificationPage,
  Page,
  TeamMember,
  WorkItemDetail,
  WorkItemSummary,
} from './types';

// Polling instead of websockets: cheap, and stale writes are still caught by version checks on the server.
const ITEM_POLL_MS = 15_000;
const LIST_POLL_MS = 30_000;

export const keys = {
  me: ['me'] as const,
  summary: ['summary'] as const,
  items: (filter: ItemFilter) => ['items', filter] as const,
  item: (id: number) => ['item', id] as const,
  activity: (id: number) => ['item', id, 'activity'] as const,
  comments: (id: number) => ['item', id, 'comments'] as const,
  members: (teamId: number) => ['team', teamId, 'members'] as const,
  notifications: ['notifications'] as const,
};

export function useMe(enabled = true) {
  return useQuery({ queryKey: keys.me, queryFn: () => api<Me>('/me'), enabled, staleTime: 60_000 });
}

export function useSummary() {
  return useQuery({
    queryKey: keys.summary,
    queryFn: () => api<DashboardSummary>('/items/summary'),
    refetchInterval: LIST_POLL_MS,
  });
}

function toQuery(filter: ItemFilter, cursor?: string, limit = 25): string {
  const params = new URLSearchParams();
  Object.entries(filter).forEach(([k, v]) => {
    if (v) params.set(k, String(v));
  });
  if (cursor) params.set('cursor', cursor);
  params.set('limit', String(limit));
  return params.toString();
}

/** Server-side filtered, cursor-paginated list. Only the pages the user asked for are ever loaded. */
export function useItems(filter: ItemFilter, limit = 25) {
  return useInfiniteQuery({
    queryKey: [...keys.items(filter), limit],
    queryFn: ({ pageParam, signal }) =>
      api<Page<WorkItemSummary>>(`/items?${toQuery(filter, pageParam, limit)}`, { signal }),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
    placeholderData: keepPreviousData,
    refetchInterval: LIST_POLL_MS,
  });
}

export function useItem(id: number) {
  return useQuery({
    queryKey: keys.item(id),
    queryFn: ({ signal }) => api<WorkItemDetail>(`/items/${id}`, { signal }),
    refetchInterval: ITEM_POLL_MS,
    retry: (count, error) => !(error instanceof ApiError && error.status === 404) && count < 2,
  });
}

export function useActivity(id: number) {
  return useInfiniteQuery({
    queryKey: keys.activity(id),
    queryFn: ({ pageParam }) =>
      api<Page<ActivityEntry>>(`/items/${id}/activity?limit=30${pageParam ? `&cursor=${pageParam}` : ''}`),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
    refetchInterval: ITEM_POLL_MS,
  });
}

export function useComments(id: number) {
  return useInfiniteQuery({
    queryKey: keys.comments(id),
    queryFn: ({ pageParam }) =>
      api<Page<CommentEntry>>(`/items/${id}/comments?limit=30${pageParam ? `&cursor=${pageParam}` : ''}`),
    initialPageParam: undefined as string | undefined,
    getNextPageParam: (last) => last.nextCursor,
    refetchInterval: ITEM_POLL_MS,
  });
}

export function useTeamMembers(teamId: number, enabled: boolean) {
  return useQuery({
    queryKey: keys.members(teamId),
    queryFn: () => api<TeamMember[]>(`/teams/${teamId}/members`),
    enabled,
    staleTime: 60_000,
  });
}

export function useNotifications() {
  return useQuery({
    queryKey: keys.notifications,
    queryFn: () => api<NotificationPage>('/notifications?limit=20'),
    refetchInterval: 20_000,
  });
}

/**
 * Mutation of one work item with optimistic UI.
 *
 * - The idempotency key is created once per user action and travels with the variables, so the
 *   automatic retries after a network failure reuse it and the server never applies the action twice.
 * - `optimistic` updates the cached item at once; on any error the previous value is restored.
 * - On 409 STALE_VERSION the server sends the current item, which replaces the cache so the user
 *   sees what changed.
 */
export function useItemMutation<V>(
  id: number,
  request: (vars: V, idempotencyKey: string) => Promise<WorkItemDetail>,
  optimistic?: (item: WorkItemDetail, vars: V) => WorkItemDetail,
) {
  const qc = useQueryClient();
  const mutation = useMutation({
    mutationFn: ({ vars, key }: { vars: V; key: string }) => request(vars, key),
    retry: (count, error) => isNetworkError(error) && count < 2,
    onMutate: async ({ vars }) => {
      await qc.cancelQueries({ queryKey: keys.item(id), exact: true });
      const previous = qc.getQueryData<WorkItemDetail>(keys.item(id));
      if (previous && optimistic) {
        qc.setQueryData(keys.item(id), optimistic(previous, vars));
      }
      return { previous };
    },
    onError: (error, _vars, context) => {
      const current = error instanceof ApiError ? (error.body?.current as WorkItemDetail | undefined) : undefined;
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
    run: (vars: V) => mutation.mutate({ vars, key: newIdempotencyKey() }),
  };
}
