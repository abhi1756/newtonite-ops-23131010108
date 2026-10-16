import { Link } from 'react-router-dom';
import { useItems, useSummary } from '../api/hooks';
import { useAuth } from '../auth/AuthContext';
import { ItemTable } from '../components/ItemTable';
import { errorMessage } from '../api/client';

const TILES = [
  { key: 'mine', label: 'Assigned to me', view: 'mine' },
  { key: 'unassignedUrgent', label: 'Unassigned urgent', view: 'unassigned_urgent' },
  { key: 'awaitingMyApproval', label: 'Awaiting my approval', view: 'awaiting_my_approval' },
  { key: 'overdue', label: 'Overdue', view: 'overdue' },
] as const;

function Panel({ title, view }: { title: string; view: string }) {
  const { data, isLoading, error } = useItems({ view }, 5);
  const items = data?.pages[0]?.items ?? [];
  return (
    <section className="card">
      <div className="card-head">
        <h2>{title}</h2>
        <Link to={`/items?view=${view}`}>View all</Link>
      </div>
      {isLoading ? <p className="muted pad">Loading...</p> : error ? <div className="alert error">{errorMessage(error)}</div> : <ItemTable items={items} compact />}
    </section>
  );
}

export function DashboardPage() {
  const { me } = useAuth();
  const summary = useSummary();
  const isLead = me?.admin || me?.teams.some((t) => t.role === 'LEAD');

  return (
    <>
      <h1>What needs attention</h1>
      <div className="tiles">
        {TILES.map((tile) => (
          <Link key={tile.key} to={`/items?view=${tile.view}`} className={`tile ${tile.key}`}>
            <span className="tile-count">{summary.data ? summary.data[tile.key] : '-'}</span>
            <span>{tile.label}</span>
          </Link>
        ))}
      </div>
      <div className="panels">
        <Panel title="Assigned to me" view="mine" />
        <Panel title="Unassigned urgent" view="unassigned_urgent" />
        {isLead && <Panel title="Awaiting my approval" view="awaiting_my_approval" />}
      </div>
    </>
  );
}
