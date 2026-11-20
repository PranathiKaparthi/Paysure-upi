import { NavLink, Outlet } from 'react-router-dom'
import { useAuth } from '../auth/AuthContext'
import { SandboxBanner } from './SandboxBanner'

/** Every entry is a working page; admin-only entries are hidden from ordinary users (the backend enforces it regardless). */
const NAV: { to: string; label: string; adminOnly?: boolean }[] = [
  { to: '/dashboard', label: 'Dashboard' },
  { to: '/send', label: 'Send Payment' },
  { to: '/request', label: 'Request Money' },
  { to: '/history', label: 'History' },
  { to: '/webhooks', label: 'Webhooks' },
  { to: '/lab', label: 'Reliability Lab', adminOnly: true },
  { to: '/reconciliation', label: 'Reconciliation', adminOnly: true },
  { to: '/risk', label: 'Risk Alerts' },
  { to: '/monitoring', label: 'Monitoring', adminOnly: true },
]

export function Layout() {
  const { user, logout } = useAuth()
  return (
    <div className="min-h-screen">
      <SandboxBanner />
      <header className="border-b border-slate-200 bg-white">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-x-6 gap-y-2 px-4 py-3">
          <div className="flex items-center gap-2">
            <span className="grid h-8 w-8 place-items-center rounded-lg bg-brand-600 text-sm font-black text-white">P</span>
            <span className="text-lg font-bold">PaySure</span>
            <span className="hidden text-xs text-slate-500 sm:inline">Reliability Lab</span>
          </div>
          <nav aria-label="Main" className="flex flex-1 flex-wrap gap-1 text-sm">
            {NAV.filter((item) => !item.adminOnly || user?.role === 'ADMIN_DEMO').map((item) => (
              <NavLink
                key={item.label}
                to={item.to}
                className={({ isActive }) =>
                  `rounded-md px-3 py-1.5 font-medium ${isActive ? 'bg-brand-50 text-brand-700' : 'text-slate-600 hover:bg-slate-100'}`
                }
              >
                {item.label}
              </NavLink>
            ))}
          </nav>
          <div className="flex items-center gap-3 text-sm">
            <div className="text-right leading-tight">
              <div className="font-medium">{user?.fullName}</div>
              <div className="text-xs text-slate-500">{user?.role === 'ADMIN_DEMO' ? 'Demo admin' : user?.email}</div>
            </div>
            <button type="button" className="btn-secondary" onClick={logout}>
              Sign out
            </button>
          </div>
        </div>
      </header>
      <main className="mx-auto max-w-6xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  )
}
