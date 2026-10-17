import { useEffect, useState } from 'react';
import { NavLink, Route, Routes, useLocation } from 'react-router-dom';
import { ServiceHealthList } from './components/ServiceHealth';
import { Dashboard } from './pages/Dashboard';
import { Inventory } from './pages/Inventory';
import { Notifications } from './pages/Notifications';
import { Operations } from './pages/Operations';
import { OrderDetail } from './pages/OrderDetail';
import { Orders } from './pages/Orders';
import { Payments } from './pages/Payments';
import { PlaceOrder } from './pages/PlaceOrder';

const NAV = [
  { to: '/', label: 'Dashboard', icon: '▦', end: true },
  { to: '/orders/new', label: 'Place order', icon: '+', end: true },
  { to: '/orders', label: 'Orders & sagas', icon: '≡', end: false },
  { to: '/inventory', label: 'Inventory', icon: '▤', end: false },
  { to: '/payments', label: 'Payments', icon: '$', end: false },
  { to: '/notifications', label: 'Notifications', icon: '✉', end: false },
  { to: '/operations', label: 'Operations', icon: '⚙', end: false },
];

type Theme = 'system' | 'light' | 'dark';

function useTheme(): [Theme, () => void] {
  const [theme, setTheme] = useState<Theme>(() => {
    try {
      return (localStorage.getItem('oms-theme') as Theme) || 'system';
    } catch {
      return 'system';
    }
  });
  useEffect(() => {
    const root = document.documentElement;
    if (theme === 'system') root.removeAttribute('data-theme');
    else root.setAttribute('data-theme', theme);
    try {
      localStorage.setItem('oms-theme', theme);
    } catch {
      /* storage unavailable */
    }
  }, [theme]);
  const cycle = () => setTheme((t) => (t === 'system' ? 'light' : t === 'light' ? 'dark' : 'system'));
  return [theme, cycle];
}

export function App() {
  const [theme, cycleTheme] = useTheme();
  const [menuOpen, setMenuOpen] = useState(false);
  const location = useLocation();
  useEffect(() => setMenuOpen(false), [location.pathname]);

  return (
    <div className="shell">
      <aside className={`sidebar ${menuOpen ? 'open' : ''}`}>
        <div className="brand">
          <span className="brand-mark" aria-hidden>
            ≡
          </span>
          <div>
            <div className="brand-name">OMS Console</div>
            <div className="brand-sub">Saga orchestration</div>
          </div>
          <button className="menu-btn" onClick={() => setMenuOpen((o) => !o)} aria-label="Toggle navigation" aria-expanded={menuOpen}>
            ☰
          </button>
        </div>
        <nav className="nav">
          {NAV.map((n) => (
            <NavLink key={n.to} to={n.to} end={n.end} className={({ isActive }) => (isActive ? 'nav-item active' : 'nav-item')}>
              <span className="nav-icon" aria-hidden>
                {n.icon}
              </span>
              {n.label}
            </NavLink>
          ))}
        </nav>
        <div className="sidebar-foot">
          <div className="sidebar-label">Services</div>
          <ServiceHealthList compact />
          <button className="theme-btn" onClick={cycleTheme} title="Cycle colour theme">
            Theme: {theme}
          </button>
        </div>
      </aside>
      <main className="main">
        <Routes>
          <Route path="/" element={<Dashboard />} />
          <Route path="/orders/new" element={<PlaceOrder />} />
          <Route path="/orders" element={<Orders />} />
          <Route path="/orders/:orderId" element={<OrderDetail />} />
          <Route path="/inventory" element={<Inventory />} />
          <Route path="/payments" element={<Payments />} />
          <Route path="/notifications" element={<Notifications />} />
          <Route path="/operations" element={<Operations />} />
          <Route path="*" element={<p>Page not found.</p>} />
        </Routes>
      </main>
    </div>
  );
}
