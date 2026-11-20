import { useState, type FormEvent } from 'react'
import { Navigate, useNavigate } from 'react-router-dom'
import { ApiError, api } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import { SandboxBanner } from '../components/SandboxBanner'

type Mode = 'login' | 'register'

const FEATURES = [
  ['Idempotent payments', 'Retries with the same Idempotency-Key never create a second transfer.'],
  ['Immutable ledger', 'Double-entry, append-only journals that always balance to zero.'],
  ['Reliability Lab', 'Inject bank timeouts and lost acknowledgements, then watch recovery.'],
  ['Self-healing reconciliation', 'PENDING payments are verified against the bank — never blindly retried.'],
]

export function LoginPage() {
  const { user, login } = useAuth()
  const navigate = useNavigate()
  const [mode, setMode] = useState<Mode>('login')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [fullName, setFullName] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  if (user) return <Navigate to="/dashboard" replace />

  async function onSubmit(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setNotice(null)
    setBusy(true)
    try {
      if (mode === 'register') {
        await api.register(email, password, fullName)
        setNotice('Account created. Signing you in…')
      }
      await login(email, password)
      navigate('/dashboard')
    } catch (err) {
      setError(err instanceof ApiError ? err.describe() : 'Something went wrong.')
    } finally {
      setBusy(false)
    }
  }

  return (
    <div className="min-h-screen">
      <SandboxBanner />
      <div className="mx-auto grid max-w-6xl gap-10 px-4 py-12 lg:grid-cols-2 lg:py-20">
        <section>
          <div className="mb-6 flex items-center gap-2">
            <span className="grid h-10 w-10 place-items-center rounded-xl bg-brand-600 text-lg font-black text-white">P</span>
            <span className="text-2xl font-bold">PaySure</span>
          </div>
          <h1 className="text-4xl font-extrabold tracking-tight text-slate-900 sm:text-5xl">
            UPI Payment <span className="text-brand-600">Reliability Lab</span>
          </h1>
          <p className="mt-4 max-w-xl text-lg text-slate-600">
            A UPI-inspired payment simulator that shows how a payment system stays correct when banks time out,
            acknowledgements get lost and callbacks arrive twice.
          </p>
          <ul className="mt-8 grid gap-4 sm:grid-cols-2">
            {FEATURES.map(([title, text]) => (
              <li key={title} className="card">
                <div className="font-semibold">{title}</div>
                <div className="mt-1 text-sm text-slate-600">{text}</div>
              </li>
            ))}
          </ul>
        </section>

        <section className="card self-start p-8">
          <div className="mb-6 flex rounded-lg bg-slate-100 p-1 text-sm font-semibold" role="tablist">
            {(['login', 'register'] as const).map((m) => (
              <button
                key={m}
                type="button"
                role="tab"
                aria-selected={mode === m}
                onClick={() => setMode(m)}
                className={`flex-1 rounded-md py-2 ${mode === m ? 'bg-white shadow-sm' : 'text-slate-500'}`}
              >
                {m === 'login' ? 'Sign in' : 'Create account'}
              </button>
            ))}
          </div>
          <form onSubmit={onSubmit} className="space-y-4" noValidate>
            {mode === 'register' && (
              <div>
                <label className="label" htmlFor="fullName">Full name</label>
                <input id="fullName" className="input" value={fullName} onChange={(e) => setFullName(e.target.value)} required />
              </div>
            )}
            <div>
              <label className="label" htmlFor="email">E-mail</label>
              <input id="email" type="email" autoComplete="username" className="input" value={email}
                onChange={(e) => setEmail(e.target.value)} required />
            </div>
            <div>
              <label className="label" htmlFor="password">Password</label>
              <input id="password" type="password" className="input" value={password}
                autoComplete={mode === 'login' ? 'current-password' : 'new-password'}
                onChange={(e) => setPassword(e.target.value)} required />
              {mode === 'register' && (
                <p className="mt-1 text-xs text-slate-500">8–64 characters with at least one letter and one digit. Never use a real banking password.</p>
              )}
            </div>
            {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}
            {notice && <p className="rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-700">{notice}</p>}
            <button type="submit" className="btn-primary w-full" disabled={busy}>
              {busy ? 'Please wait…' : mode === 'login' ? 'Sign in' : 'Create sandbox account'}
            </button>
          </form>
          <div className="mt-6 rounded-lg bg-slate-50 p-3 text-xs text-slate-600">
            <div className="font-semibold text-slate-700">Demo users (seeded in dev/docker)</div>
            ananya@example.com · rahul@example.com · labadmin@example.com (demo admin) — password from{' '}
            <code>PAYSURE_DEMO_PASSWORD</code>.
          </div>
        </section>
      </div>
    </div>
  )
}
