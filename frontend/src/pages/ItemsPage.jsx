import { useEffect, useMemo, useState } from 'react';
import { useSearchParams } from 'react-router-dom';
import { useItems } from '../api/hooks';
import { ITEM_TYPES, PRIORITIES, STATUSES } from '../api/model';
import { useAuth } from '../auth/AuthContext';
import { ItemTable } from '../components/ItemTable';
import { errorMessage } from '../api/client';
import { label } from '../lib/format';

const FILTER_KEYS = ['view', 'teamId', 'status', 'priority', 'type', 'assignee', 'q'];
const ACTIVE = 'OPEN,IN_PROGRESS,AWAITING_APPROVAL';

/** All filters live in the URL, so a filtered view can be bookmarked or shared. */
export function ItemsPage() {
  const { me } = useAuth();
  const [params, setParams] = useSearchParams();
  const filter = useMemo(() => {
    const f = {};
    FILTER_KEYS.forEach((k) => {
      const v = params.get(k);
      if (v) f[k] = v;
    });
    return f;
  }, [params]);

  // Debounce typing so the server is queried once per pause, not once per keystroke.
  const [search, setSearch] = useState(filter.q ?? '');
  useEffect(() => setSearch(filter.q ?? ''), [filter.q]);
  useEffect(() => {
    const handle = setTimeout(() => {
      if ((filter.q ?? '') !== search.trim()) update('q', search.trim());
    }, 300);
    return () => clearTimeout(handle);
  }, [search]);

  const update = (key, value) => {
    const next = new URLSearchParams(params);
    if (value) next.set(key, value);
    else next.delete(key);
    setParams(next, { replace: true });
  };

  const query = useItems(filter);
  const items = query.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <>
      <h1>Work items</h1>
      <div className="filters card">
        <input
          className="search"
          placeholder="Search title or description, or #id"
          value={search}
          onChange={(e) => setSearch(e.target.value)}
        />
        <select value={filter.view ?? ''} onChange={(e) => update('view', e.target.value)}>
          <option value="">All work</option>
          <option value="mine">Assigned to me</option>
          <option value="unassigned_urgent">Unassigned urgent</option>
          <option value="awaiting_my_approval">Awaiting my approval</option>
          <option value="overdue">Overdue</option>
        </select>
        <select value={filter.teamId ?? ''} onChange={(e) => update('teamId', e.target.value)}>
          <option value="">All my teams</option>
          {me?.teams.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
        <select value={filter.status ?? ''} onChange={(e) => update('status', e.target.value)}>
          <option value="">Any status</option>
          <option value={ACTIVE}>Active (not resolved)</option>
          {STATUSES.map((s) => (
            <option key={s} value={s}>
              {label(s)}
            </option>
          ))}
        </select>
        <select value={filter.priority ?? ''} onChange={(e) => update('priority', e.target.value)}>
          <option value="">Any priority</option>
          <option value="HIGH,URGENT">High or urgent</option>
          {PRIORITIES.map((p) => (
            <option key={p} value={p}>
              {label(p)}
            </option>
          ))}
        </select>
        <select value={filter.type ?? ''} onChange={(e) => update('type', e.target.value)}>
          <option value="">Any type</option>
          {ITEM_TYPES.map((t) => (
            <option key={t} value={t}>
              {label(t)}
            </option>
          ))}
        </select>
        <select value={filter.assignee ?? ''} onChange={(e) => update('assignee', e.target.value)}>
          <option value="">Anyone</option>
          <option value="me">Me</option>
          <option value="none">Unassigned</option>
        </select>
        {Object.keys(filter).length > 0 && (
          <button className="link" onClick={() => setParams(new URLSearchParams(), { replace: true })}>
            Clear filters
          </button>
        )}
      </div>

      {query.error && <div className="alert error">{errorMessage(query.error)}</div>}
      <section className={`card ${query.isPlaceholderData ? 'refreshing' : ''}`}>
        {query.isLoading ? <p className="muted pad">Loading...</p> : <ItemTable items={items} />}
        {query.hasNextPage && (
          <div className="pad center">
            <button onClick={() => query.fetchNextPage()} disabled={query.isFetchingNextPage}>
              {query.isFetchingNextPage ? 'Loading...' : 'Load more'}
            </button>
          </div>
        )}
      </section>
      <p className="muted small">
        Showing {items.length} item{items.length === 1 ? '' : 's'}
        {query.hasNextPage ? ' (more available)' : ''}. Lists refresh automatically every 30 seconds.
      </p>
    </>
  );
}
