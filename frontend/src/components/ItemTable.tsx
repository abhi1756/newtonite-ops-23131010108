import { Link } from 'react-router-dom';
import type { WorkItemSummary } from '../api/types';
import { label, timeAgo } from '../lib/format';
import { PriorityBadge, StatusBadge } from './Badges';

export function ItemTable({ items, compact = false }: { items: WorkItemSummary[]; compact?: boolean }) {
  if (items.length === 0) {
    return <p className="muted pad">No matching work items.</p>;
  }
  return (
    <div className="table-wrap">
      <table className="items">
        <thead>
          <tr>
            <th>#</th>
            <th>Title</th>
            {!compact && <th>Team</th>}
            {!compact && <th>Type</th>}
            <th>Priority</th>
            <th>Status</th>
            <th>Assignee</th>
            {!compact && <th>Due</th>}
            <th>Updated</th>
          </tr>
        </thead>
        <tbody>
          {items.map((item) => (
            <tr key={item.id}>
              <td className="muted">{item.id}</td>
              <td>
                <Link to={`/items/${item.id}`}>{item.title}</Link>
                {compact && <div className="muted small">{item.teamName}</div>}
              </td>
              {!compact && <td>{item.teamName}</td>}
              {!compact && <td>{label(item.type)}</td>}
              <td>
                <PriorityBadge priority={item.priority} />
              </td>
              <td>
                <StatusBadge status={item.status} />
              </td>
              <td>{item.assignee?.name ?? <span className="muted">Unassigned</span>}</td>
              {!compact && (
                <td className={item.overdue ? 'overdue' : ''}>
                  {item.dueAt ? timeAgo(item.dueAt) : <span className="muted">-</span>}
                </td>
              )}
              <td className="muted">{timeAgo(item.updatedAt)}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}
