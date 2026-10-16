import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useMutation, useQueryClient } from '@tanstack/react-query';
import { api, errorMessage, isNetworkError, newIdempotencyKey } from '../api/client';
import { ITEM_TYPES, PRIORITIES } from '../api/model';
import { useAuth } from '../auth/AuthContext';
import { fromLocalInput, label } from '../lib/format';

export function NewItemPage() {
  const { me } = useAuth();
  const navigate = useNavigate();
  const qc = useQueryClient();
  const teams = (me?.teams ?? []).filter((t) => t.role !== 'VIEWER');

  const [teamId, setTeamId] = useState('');
  const [title, setTitle] = useState('');
  const [description, setDescription] = useState('');
  const [type, setType] = useState('TASK');
  const [priority, setPriority] = useState('MEDIUM');
  const [due, setDue] = useState('');
  // One key per form: a double submit or a retry after a timeout cannot create two items.
  const [idempotencyKey] = useState(newIdempotencyKey);

  const create = useMutation({
    mutationFn: () =>
      api('/items', {
        method: 'POST',
        idempotencyKey,
        body: { teamId: teamId || teams[0]?.id, title, description, type, priority, dueAt: fromLocalInput(due) },
      }),
    retry: (count, error) => isNetworkError(error) && count < 2,
    onSuccess: (item) => {
      qc.invalidateQueries({ queryKey: ['items'] });
      qc.invalidateQueries({ queryKey: ['summary'] });
      navigate(`/items/${item.id}`);
    },
  });

  const submit = (e) => {
    e.preventDefault();
    create.mutate();
  };

  if (me && teams.length === 0) {
    return <div className="alert">You are not a Member or Lead of any team, so you cannot create work items.</div>;
  }

  return (
    <form className="card pad narrow" onSubmit={submit}>
      <h1>New work item</h1>
      {create.error && <div className="alert error">{errorMessage(create.error)}</div>}
      <label>
        Team
        <select value={teamId || teams[0]?.id || ''} onChange={(e) => setTeamId(Number(e.target.value))}>
          {teams.map((t) => (
            <option key={t.id} value={t.id}>
              {t.name}
            </option>
          ))}
        </select>
      </label>
      <label>
        Title
        <input value={title} onChange={(e) => setTitle(e.target.value)} required maxLength={200} autoFocus />
      </label>
      <div className="row">
        <label>
          Type
          <select value={type} onChange={(e) => setType(e.target.value)}>
            {ITEM_TYPES.map((t) => (
              <option key={t} value={t}>
                {label(t)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Priority
          <select value={priority} onChange={(e) => setPriority(e.target.value)}>
            {PRIORITIES.map((p) => (
              <option key={p} value={p}>
                {label(p)}
              </option>
            ))}
          </select>
        </label>
        <label>
          Due (optional)
          <input type="datetime-local" value={due} onChange={(e) => setDue(e.target.value)} />
        </label>
      </div>
      <label>
        Description
        <textarea rows={6} value={description} onChange={(e) => setDescription(e.target.value)} />
      </label>
      {(type === 'PAYMENT' || type === 'COMPLIANCE') && (
        <p className="muted small">{label(type)} items need a Lead's approval before they can be resolved.</p>
      )}
      <button className="primary" disabled={create.isPending}>
        {create.isPending ? 'Creating...' : 'Create'}
      </button>
    </form>
  );
}
