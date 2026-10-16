import { label } from '../lib/format';

export function StatusBadge({ status }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{label(status)}</span>;
}

export function PriorityBadge({ priority }) {
  return <span className={`badge priority-${priority.toLowerCase()}`}>{label(priority)}</span>;
}
