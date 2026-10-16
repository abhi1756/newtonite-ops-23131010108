import { useState } from 'react';
import { useNavigate } from 'react-router-dom';
import { useQueryClient } from '@tanstack/react-query';
import { api } from '../api/client';
import { keys, useNotifications } from '../api/hooks';
import { timeAgo } from '../lib/format';

export function NotificationsBell() {
  const { data } = useNotifications();
  const [open, setOpen] = useState(false);
  const navigate = useNavigate();
  const qc = useQueryClient();

  const refresh = () => qc.invalidateQueries({ queryKey: keys.notifications });

  const openNotification = async (id, itemId) => {
    setOpen(false);
    await api(`/notifications/${id}/read`, { method: 'POST' }).catch(() => undefined);
    refresh();
    if (itemId) navigate(`/items/${itemId}`);
  };

  const markAll = async () => {
    await api('/notifications/read-all', { method: 'POST' }).catch(() => undefined);
    refresh();
  };

  const unread = data?.unreadCount ?? 0;
  return (
    <div className="bell">
      <button className="ghost" onClick={() => setOpen((o) => !o)} aria-expanded={open}>
        Notifications{unread > 0 && <span className="count">{unread}</span>}
      </button>
      {open && (
        <div className="dropdown">
          <div className="dropdown-head">
            <strong>Notifications</strong>
            {unread > 0 && (
              <button className="link" onClick={markAll}>
                Mark all read
              </button>
            )}
          </div>
          {data?.items.length ? (
            data.items.map((n) => (
              <button
                key={n.id}
                className={`notification ${n.read ? '' : 'unread'}`}
                onClick={() => openNotification(n.id, n.itemId)}
              >
                <span>{n.message}</span>
                <small>{timeAgo(n.createdAt)}</small>
              </button>
            ))
          ) : (
            <p className="muted pad">Nothing yet.</p>
          )}
        </div>
      )}
    </div>
  );
}
