import { NavLink, Outlet } from 'react-router-dom';
import { useAuth } from '../auth/AuthContext';
import { NotificationsBell } from './NotificationsBell';

export function Layout() {
  const { me, logout } = useAuth();
  return (
    <>
      <header className="topbar">
        <div className="topbar-inner">
          <span className="brand">Newtonite Ops</span>
          <nav>
            <NavLink to="/" end>
              Dashboard
            </NavLink>
            <NavLink to="/items" end>
              Work items
            </NavLink>
            <NavLink to="/items/new">New item</NavLink>
          </nav>
          <div className="spacer" />
          <NotificationsBell />
          <span className="muted user">
            {me?.displayName}
            {me?.admin && ' (admin)'}
          </span>
          <button className="ghost" onClick={logout}>
            Log out
          </button>
        </div>
      </header>
      <main className="container">
        <Outlet />
      </main>
    </>
  );
}
