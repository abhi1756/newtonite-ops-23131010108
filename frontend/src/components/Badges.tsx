import type { Priority, Status } from '../api/types';
import { label } from '../lib/format';

export function StatusBadge({ status }: { status: Status }) {
  return <span className={`badge status-${status.toLowerCase()}`}>{label(status)}</span>;
}

export function PriorityBadge({ priority }: { priority: Priority }) {
  return <span className={`badge priority-${priority.toLowerCase()}`}>{label(priority)}</span>;
}
