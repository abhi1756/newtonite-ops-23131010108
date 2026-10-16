import { useState } from 'react';
import { Link, useParams } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, ApiError, errorMessage, isNetworkError, newIdempotencyKey } from '../api/client';
import { keys, useActivity, useComments, useItem, useItemMutation, useTeamMembers } from '../api/hooks';
import { ITEM_TYPES, PRIORITIES } from '../api/model';
import { useAuth } from '../auth/AuthContext';
import { PriorityBadge, StatusBadge } from '../components/Badges';
import { dateTime, fromLocalInput, label, timeAgo, toLocalInput } from '../lib/format';

export function ItemDetailPage() {
  const id = Number(useParams().id);
  const { data: item, error, isLoading } = useItem(id);

  if (isLoading) return <p className="muted">Loading...</p>;
  if (error instanceof ApiError && error.status === 404) {
    return (
      <div className="card pad">
        <h1>Not found</h1>
        <p className="muted">This item does not exist or belongs to a team you are not part of.</p>
        <Link to="/items">Back to work items</Link>
      </div>
    );
  }
  if (error || !item) return <div className="alert error">{errorMessage(error)}</div>;
  return <ItemView item={item} />;
}

/** @param {{ item: import('../api/model').WorkItemDetail }} props */
function ItemView({ item }) {
  const { me } = useAuth();
  const [editing, setEditing] = useState(false);
  const p = item.permissions;

  const claim = useItemMutation(
    item.id,
    (_vars, key) => api(`/items/${item.id}/claim`, { method: 'POST', idempotencyKey: key }),
    (current) => ({
      ...current,
      assignee: me ? { id: me.id, name: me.displayName } : current.assignee,
      permissions: { ...current.permissions, canClaim: false },
    }),
  );
  const transition = useItemMutation(
    item.id,
    (vars, key) => api(`/items/${item.id}/transition`, { method: 'POST', body: vars, idempotencyKey: key }),
    (current, vars) => ({ ...current, status: vars.to, permissions: { ...current.permissions, transitions: [] } }),
  );
  const assign = useItemMutation(item.id, (vars, key) =>
    api(`/items/${item.id}/assign`, { method: 'POST', body: vars, idempotencyKey: key }),
  );

  const failures = [claim, transition, assign].filter((m) => m.error);

  return (
    <>
      <p className="muted small">
        <Link to="/items">Work items</Link> / #{item.id} / {item.teamName}
      </p>
      <div className="title-row">
        <h1>{item.title}</h1>
        <div className="badges">
          <StatusBadge status={item.status} />
          <PriorityBadge priority={item.priority} />
          <span className="badge">{label(item.type)}</span>
          {item.overdue && <span className="badge overdue-badge">Overdue</span>}
        </div>
      </div>

      {failures.map((m, i) => (
        <div key={i} className="alert error">
          {errorMessage(m.error)}
          {m.error instanceof ApiError && m.error.code === 'STALE_VERSION' && ' The latest version is now shown.'}
          <button className="link" onClick={() => m.reset()}>
            Dismiss
          </button>
        </div>
      ))}

      <div className="detail-grid">
        <section className="card pad">
          <dl className="facts">
            <dt>Assignee</dt>
            <dd>{item.assignee?.name ?? <span className="muted">Unassigned</span>}</dd>
            <dt>Team</dt>
            <dd>{item.teamName}</dd>
            <dt>Created by</dt>
            <dd>
              {item.createdBy.name}, {timeAgo(item.createdAt)}
            </dd>
            <dt>Due</dt>
            <dd className={item.overdue ? 'overdue' : ''}>{item.dueAt ? dateTime(item.dueAt) : '-'}</dd>
            <dt>Last change</dt>
            <dd>
              {timeAgo(item.updatedAt)} <span className="muted small">(version {item.version})</span>
            </dd>
          </dl>

          <div className="actions">
            {p.canClaim && (
              <button className="primary" onClick={() => claim.run()} disabled={claim.isPending}>
                {claim.isPending ? 'Claiming...' : 'Claim'}
              </button>
            )}
            {p.transitions.map((t) => (
              <TransitionButton
                key={t.to}
                option={t}
                busy={transition.isPending}
                onRun={(note) => transition.run({ to: t.to, note, version: item.version })}
              />
            ))}
            {p.canEdit && !editing && <button onClick={() => setEditing(true)}>Edit</button>}
          </div>
          {p.canAssign && (
            <AssignControl
              item={item}
              busy={assign.isPending}
              onAssign={(assigneeId) => assign.run({ assigneeId, version: item.version })}
            />
          )}
          {!p.canClaim && !p.canEdit && p.transitions.length === 0 && !p.canAssign && (
            <p className="muted small">You can view this item but your role does not allow changes.</p>
          )}

          {editing ? (
            <EditForm item={item} onDone={() => setEditing(false)} />
          ) : (
            <div className="description">{item.description || <span className="muted">No description.</span>}</div>
          )}
        </section>

        <section className="card pad">
          <Comments itemId={item.id} canComment={p.canComment} />
          <h2>History</h2>
          <Activity itemId={item.id} />
        </section>
      </div>
    </>
  );
}

function TransitionButton({ option, busy, onRun }) {
  const [asking, setAsking] = useState(false);
  const [note, setNote] = useState('');
  if (!option.noteRequired) {
    return (
      <button onClick={() => onRun()} disabled={busy}>
        {label(option.action)}
      </button>
    );
  }
  if (!asking) {
    return (
      <button onClick={() => setAsking(true)} disabled={busy}>
        {label(option.action)}...
      </button>
    );
  }
  return (
    <span className="inline-form">
      <input autoFocus placeholder="Reason (required)" value={note} onChange={(e) => setNote(e.target.value)} />
      <button disabled={!note.trim() || busy} onClick={() => onRun(note.trim())}>
        {label(option.action)}
      </button>
      <button className="link" onClick={() => setAsking(false)}>
        Cancel
      </button>
    </span>
  );
}

function AssignControl({ item, busy, onAssign }) {
  const members = useTeamMembers(item.teamId, true);
  const candidates = (members.data ?? []).filter((m) => m.role !== 'VIEWER');
  return (
    <label className="assign">
      Assign to
      <select
        value={item.assignee?.id ?? ''}
        disabled={busy || members.isLoading}
        onChange={(e) => onAssign(e.target.value ? Number(e.target.value) : null)}
      >
        <option value="" disabled={item.status !== 'OPEN'}>
          Unassigned
        </option>
        {candidates.map((m) => (
          <option key={m.id} value={m.id}>
            {m.name} ({label(m.role)})
          </option>
        ))}
      </select>
    </label>
  );
}

/** The editable fields of an item, as form values. */
function draftOf(item) {
  return {
    title: item.title,
    description: item.description,
    type: item.type,
    priority: item.priority,
    due: toLocalInput(item.dueAt),
  };
}

/**
 * The edit form remembers the version the user started from and sends ONLY the fields the user changed.
 * If someone else saves first, the server rejects the save (409); the form keeps the user's draft and
 * offers to re-apply it on the latest version. Because only changed fields are sent, re-applying cannot
 * silently undo the other person's edits to fields this user never touched.
 */
function EditForm({ item, onDone }) {
  const [baseVersion, setBaseVersion] = useState(item.version);
  const [initial] = useState(() => draftOf(item));
  const [draft, setDraft] = useState(initial);
  const changed = Object.keys(draft).filter((k) => draft[k] !== initial[k]);

  const save = useItemMutation(
    item.id,
    ({ changes, version }, key) =>
      api(`/items/${item.id}`, {
        method: 'PATCH',
        idempotencyKey: key,
        body: {
          version,
          title: changes.title,
          description: changes.description,
          type: changes.type,
          priority: changes.priority,
          dueAt: changes.due ? fromLocalInput(changes.due) : undefined,
          clearDueAt: changes.due === '' ? true : undefined,
        },
      }),
    (current, { changes }) => {
      const fields = { ...changes };
      delete fields.due; // the form value; the cached item uses dueAt and is refreshed from the response
      return { ...current, ...fields };
    },
  );
  const conflict = save.error instanceof ApiError && save.error.code === 'STALE_VERSION';

  const submit = (e) => {
    e.preventDefault();
    if (changed.length === 0) {
      onDone();
      return;
    }
    const changes = Object.fromEntries(changed.map((k) => [k, draft[k]]));
    save.mutate({ vars: { changes, version: baseVersion }, key: newIdempotencyKey() }, { onSuccess: onDone });
  };
  const set = (key, value) => setDraft((d) => ({ ...d, [key]: value }));

  return (
    <form className="edit-form" onSubmit={submit}>
      {conflict ? (
        <div className="alert warning">
          Someone changed this item while you were editing (now version {item.version}). The page shows their
          changes; your draft below is kept. Saving again applies only the fields you changed
          ({changed.map(label).join(', ') || 'none'}).
          <div className="actions">
            <button
              type="button"
              onClick={() => {
                setBaseVersion(item.version);
                save.reset();
              }}
            >
              Keep my draft and review again
            </button>
            <button type="button" className="link" onClick={onDone}>
              Discard my draft
            </button>
          </div>
        </div>
      ) : (
        save.error && <div className="alert error">{errorMessage(save.error)}</div>
      )}
      <label>
        Title
        <input value={draft.title} onChange={(e) => set('title', e.target.value)} required maxLength={200} />
      </label>
      <div className="row">
        <label>
          Type
          <select value={draft.type} onChange={(e) => set('type', e.target.value)}>
            {ITEM_TYPES.map((t) => (
              <option key={t} value={t}>
                {label(t)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Priority
          <select value={draft.priority} onChange={(e) => set('priority', e.target.value)}>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {label(p)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Due
          <input type="datetime-local" value={draft.due} onChange={(e) => set('due', e.target.value)} />
        </label>
      </div>
      <label>
        Description
        <textarea rows={6} value={draft.description} onChange={(e) => set('description', e.target.value)} />
      </label>
      <div className="actions">
        <button className="primary" disabled={save.isPending || conflict}>
          {save.isPending ? 'Saving...' : 'Save'}
        </button>
        <button type="button" className="link" onClick={onDone}>
          Cancel
        </button>
      </div>
    </form>
  );
}

function Comments({ itemId, canComment }) {
  const comments = useComments(itemId);
  const qc = useQueryClient();
  const [body, setBody] = useState('');
  const [key, setKey] = useState(newIdempotencyKey);
  const add = useMutation({
    // The key is fixed for this draft, so a retry after a network error cannot post the comment twice.
    mutationFn: (text) => api(`/items/${itemId}/comments`, { method: 'POST', body: { body: text }, idempotencyKey: key }),
    retry: (count, error) => isNetworkError(error) && count < 2,
    onSuccess: () => {
      setBody('');
      setKey(newIdempotencyKey());
      qc.invalidateQueries({ queryKey: keys.comments(itemId) });
      qc.invalidateQueries({ queryKey: keys.activity(itemId) });
    },
  });
  const all = comments.data?.pages.flatMap((p) => p.items) ?? [];

  return (
    <>
      <h2>Comments</h2>
      {canComment && (
        <form
          className="comment-form"
          onSubmit={(e) => {
            e.preventDefault();
            if (body.trim()) add.mutate(body.trim());
          }}
        >
          <textarea rows={2} placeholder="Add a comment" value={body} onChange={(e) => setBody(e.target.value)} />
          <button disabled={add.isPending || !body.trim()}>{add.isPending ? 'Posting...' : 'Comment'}</button>
          {add.error && <div className="alert error">{errorMessage(add.error)}</div>}
        </form>
      )}
      {all.length === 0 && <p className="muted">No comments yet.</p>}
      <ul className="timeline">
        {all.map((c) => (
          <li key={c.id}>
            <strong>{c.authorName}</strong> <span className="muted small">{timeAgo(c.createdAt)}</span>
            <div className="comment-body">{c.body}</div>
          </li>
        ))}
      </ul>
      {comments.hasNextPage && (
        <button className="link" onClick={() => comments.fetchNextPage()}>
          Older comments
        </button>
      )}
    </>
  );
}

function Activity({ itemId }) {
  const activity = useActivity(itemId);
  const events = activity.data?.pages.flatMap((p) => p.items) ?? [];
  return (
    <>
      <ul className="timeline">
        {events.map((e) => (
          <li key={e.id}>
            <strong>{e.actorName ?? 'System'}</strong> {describe(e)}{' '}
            <span className="muted small" title={dateTime(e.createdAt)}>
              {timeAgo(e.createdAt)}
            </span>
          </li>
        ))}
      </ul>
      {activity.hasNextPage && (
        <button className="link" onClick={() => activity.fetchNextPage()}>
          Older history
        </button>
      )}
    </>
  );
}

/** Human-readable sentence for one activity event (see ActivityService on the server for the data shapes). */
function describe(e) {
  const d = e.data;
  switch (e.type) {
    case 'CREATED':
      return 'created this item';
    case 'ASSIGNED':
      if (d.via === 'CLAIM') return 'claimed it';
      return d.toName ? `assigned it to ${d.toName}${d.fromName ? ` (was ${d.fromName})` : ''}` : `unassigned ${d.fromName}`;
    case 'STATUS_CHANGED':
      return `moved it from ${label(d.from)} to ${label(d.to)}${d.note ? `: "${d.note}"` : ''}`;
    case 'PRIORITY_CHANGED':
      return `changed priority from ${label(d.from)} to ${label(d.to)}`;
    case 'UPDATED':
      return `edited ${Object.keys(d.changes ?? {}).join(', ')}`;
    case 'COMMENTED':
      return `commented: "${d.excerpt}"`;
    default:
      return label(e.type);
  }
}
