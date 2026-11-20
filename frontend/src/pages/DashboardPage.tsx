import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type { Account, LedgerEntry, ResolvedVpa } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'
import { errorText } from '../lib/format'
import { formatPaise, parseRupeesToPaise } from '../lib/money'


export function DashboardPage() {
  const { user } = useAuth()
  const [accounts, setAccounts] = useState<Account[] | null>(null)
  const [loadError, setLoadError] = useState<string | null>(null)

  const refresh = useCallback(async () => {
    try {
      setAccounts(await api.listAccounts())
      setLoadError(null)
    } catch (err) {
      setLoadError(errorText(err))
    }
  }, [])

  useEffect(() => {
    let cancelled = false
    api.listAccounts().then(
      (list) => {
        if (!cancelled) setAccounts(list)
      },
      (err: unknown) => {
        if (!cancelled) setLoadError(errorText(err))
      },
    )
    return () => {
      cancelled = true
    }
  }, [])

  const total = accounts?.reduce((sum, a) => sum + a.balancePaise, 0) ?? 0
  const available = accounts?.reduce((sum, a) => sum + a.availablePaise, 0) ?? 0
  const vpaCount = accounts?.reduce((sum, a) => sum + a.vpas.length, 0) ?? 0

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Welcome, {user?.fullName.split(' ')[0]}</h1>
        <p className="text-sm text-slate-500">Your simulated accounts and virtual payment addresses.</p>
      </div>

      {loadError && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{loadError}</p>}

      <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-label="Summary">
        <Stat label="Total balance" value={accounts ? formatPaise(total) : '—'} />
        <Stat label="Available" value={accounts ? formatPaise(available) : '—'} hint="Balance minus funds held for in-flight payments" />
        <Stat label="Accounts" value={accounts ? String(accounts.length) : '—'} />
        <Stat label="VPAs" value={accounts ? String(vpaCount) : '—'} />
      </section>

      <div className="grid gap-6 lg:grid-cols-3">
        <section className="space-y-4 lg:col-span-2" aria-label="Accounts">
          {accounts === null && !loadError && <div className="card text-sm text-slate-500">Loading accounts…</div>}
          {accounts?.length === 0 && (
            <div className="card text-sm text-slate-600">
              You have no sandbox accounts yet. Open one to receive simulated demo funds.
            </div>
          )}
          {accounts?.map((a) => <AccountCard key={a.id} account={a} onChanged={refresh} />)}
        </section>

        <aside className="space-y-4">
          <OpenAccountForm onCreated={refresh} />
          <ResolveVpaCard />
          {user?.role === 'ADMIN_DEMO' && <TopUpCard accounts={accounts ?? []} onDone={refresh} />}
        </aside>
      </div>
    </div>
  )
}

function Stat({ label, value, hint }: { label: string; value: string; hint?: string }) {
  return (
    <div className="card" title={hint}>
      <div className="label">{label}</div>
      <div className="text-2xl font-bold tabular-nums">{value}</div>
    </div>
  )
}

function AccountCard({ account, onChanged }: { account: Account; onChanged: () => Promise<void> }) {
  const [handle, setHandle] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [ledger, setLedger] = useState<LedgerEntry[] | null>(null)

  async function run(action: () => Promise<unknown>) {
    setBusy(true)
    setError(null)
    try {
      await action()
      await onChanged()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function toggleLedger() {
    if (ledger) return setLedger(null)
    try {
      setLedger(await api.ledger(account.id))
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <article className="card" aria-label={account.displayName}>
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <div className="flex items-center gap-2">
            <h2 className="text-lg font-semibold">{account.displayName}</h2>
            <StatusBadge status={account.status} />
          </div>
          <div className="mt-0.5 font-mono text-xs text-slate-500">
            {account.maskedAccountNumber} · IFSC {account.ifsc}
          </div>
        </div>
        <div className="text-right">
          <div className="text-2xl font-bold tabular-nums" data-testid="balance">{formatPaise(account.balancePaise)}</div>
          <div className="text-xs text-slate-500">
            Available {formatPaise(account.availablePaise)}
            {account.reservedPaise > 0 && <> · Held {formatPaise(account.reservedPaise)}</>}
          </div>
        </div>
      </div>

      <div className="mt-4 flex flex-wrap gap-2">
        {account.vpas.length === 0 && <span className="text-sm text-slate-500">No VPA linked yet.</span>}
        {account.vpas.map((v) => (
          <span key={v.id} className={`rounded-full px-3 py-1 font-mono text-sm ${v.status === 'ACTIVE' ? 'bg-brand-50 text-brand-700' : 'bg-slate-100 text-slate-400 line-through'}`}>
            {v.vpa}
          </span>
        ))}
      </div>

      <form
        className="mt-4 flex flex-wrap gap-2"
        onSubmit={(e: FormEvent) => {
          e.preventDefault()
          void run(async () => {
            await api.linkVpa(account.id, handle)
            setHandle('')
          })
        }}
      >
        <div className="flex min-w-48 flex-1 items-center rounded-lg border border-slate-300 bg-white pr-3 text-sm shadow-sm focus-within:ring-2 focus-within:ring-brand-100">
          <input
            aria-label={`New VPA handle for ${account.displayName}`}
            className="w-full rounded-lg px-3 py-2 outline-none"
            placeholder="new handle"
            value={handle}
            onChange={(e) => setHandle(e.target.value)}
            disabled={account.status !== 'ACTIVE'}
          />
          <span className="text-slate-400">@paysure</span>
        </div>
        <button type="submit" className="btn-secondary" disabled={busy || !handle || account.status !== 'ACTIVE'}>
          Link VPA
        </button>
        <button type="button" className="btn-secondary" onClick={() => void toggleLedger()}>
          {ledger ? 'Hide ledger' : 'Ledger'}
        </button>
        <button
          type="button"
          className="btn-secondary"
          disabled={busy}
          onClick={() => void run(() => (account.status === 'ACTIVE' ? api.deactivateAccount(account.id) : api.activateAccount(account.id)))}
        >
          {account.status === 'ACTIVE' ? 'Deactivate' : 'Activate'}
        </button>
      </form>
      {error && <p role="alert" className="mt-3 rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}

      {ledger && (
        <div className="mt-4 overflow-x-auto">
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase text-slate-500">
              <tr>
                <th className="py-2">Time</th>
                <th>Direction</th>
                <th className="text-right">Amount</th>
                <th className="text-right">Balance after</th>
              </tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {ledger.map((e) => (
                <tr key={e.id}>
                  <td className="py-2 text-slate-500">{new Date(e.createdAt).toLocaleString('en-IN')}</td>
                  <td className={e.direction === 'CREDIT' ? 'text-emerald-700' : 'text-rose-700'}>{e.direction}</td>
                  <td className="text-right tabular-nums">{formatPaise(e.amountPaise)}</td>
                  <td className="text-right tabular-nums">{formatPaise(e.balanceAfterPaise)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </article>
  )
}

function OpenAccountForm({ onCreated }: { onCreated: () => Promise<void> }) {
  const [name, setName] = useState('')
  const [handle, setHandle] = useState('')
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      await api.openAccount(name, handle)
      setName('')
      setHandle('')
      await onCreated()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <form className="card space-y-3" onSubmit={submit} aria-label="Open sandbox account">
      <h2 className="font-semibold">Open sandbox account</h2>
      <p className="text-xs text-slate-500">New accounts receive simulated demo funds from the sandbox treasury.</p>
      <div>
        <label className="label" htmlFor="acc-name">Account name</label>
        <input id="acc-name" className="input" value={name} onChange={(e) => setName(e.target.value)} placeholder="Savings" />
      </div>
      <div>
        <label className="label" htmlFor="acc-handle">VPA handle (optional)</label>
        <input id="acc-handle" className="input" value={handle} onChange={(e) => setHandle(e.target.value)} placeholder="ananya" />
      </div>
      {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}
      <button type="submit" className="btn-primary w-full" disabled={busy || !name.trim()}>
        {busy ? 'Opening…' : 'Open account'}
      </button>
    </form>
  )
}

function ResolveVpaCard() {
  const [vpa, setVpa] = useState('')
  const [result, setResult] = useState<ResolvedVpa | null>(null)
  const [error, setError] = useState<string | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    setResult(null)
    setError(null)
    try {
      setResult(await api.resolveVpa(vpa.trim()))
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <form className="card space-y-3" onSubmit={submit} aria-label="Verify payee VPA">
      <h2 className="font-semibold">Verify a payee VPA</h2>
      <input aria-label="Payee VPA" className="input font-mono" value={vpa} onChange={(e) => setVpa(e.target.value)} placeholder="rahul@paysure" />
      <button type="submit" className="btn-secondary w-full" disabled={!vpa.trim()}>Verify</button>
      {result && (
        <p className="rounded-lg bg-emerald-50 px-3 py-2 text-sm text-emerald-800">
          <span className="font-mono">{result.vpa}</span> belongs to <strong>{result.payeeName}</strong>
        </p>
      )}
      {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}
    </form>
  )
}

function TopUpCard({ accounts, onDone }: { accounts: Account[]; onDone: () => Promise<void> }) {
  const [accountId, setAccountId] = useState('')
  const [amount, setAmount] = useState('')
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)

  async function submit(e: FormEvent) {
    e.preventDefault()
    const paise = parseRupeesToPaise(amount)
    if (paise === null) {
      setMessage({ ok: false, text: 'Enter a positive rupee amount with at most two decimals.' })
      return
    }
    try {
      const updated = await api.devTopUp(accountId.trim(), paise, 'Dashboard sandbox top-up')
      setMessage({ ok: true, text: `Credited ${formatPaise(paise)}. New balance ${formatPaise(updated.balancePaise)}.` })
      setAmount('')
      await onDone()
    } catch (err) {
      setMessage({ ok: false, text: errorText(err) })
    }
  }

  return (
    <form className="card space-y-3 border-dashed border-amber-300" onSubmit={submit} aria-label="Sandbox top-up">
      <h2 className="font-semibold">Sandbox top-up <span className="text-xs font-normal text-amber-700">(demo admin)</span></h2>
      <p className="text-xs text-slate-500">Development-only. Posts a balanced ledger journal from the sandbox treasury.</p>
      <div>
        <label className="label" htmlFor="topup-account">Account ID</label>
        <input id="topup-account" list="own-accounts" className="input font-mono text-xs" value={accountId}
          onChange={(e) => setAccountId(e.target.value)} placeholder="any customer account id" />
        <datalist id="own-accounts">
          {accounts.map((a) => <option key={a.id} value={a.id}>{a.displayName}</option>)}
        </datalist>
      </div>
      <div>
        <label className="label" htmlFor="topup-amount">Amount (₹)</label>
        <input id="topup-amount" className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="500.00" />
      </div>
      <button type="submit" className="btn-secondary w-full" disabled={!accountId.trim() || !amount}>Top up</button>
      {message && (
        <p role={message.ok ? 'status' : 'alert'} className={`rounded-lg px-3 py-2 text-sm ${message.ok ? 'bg-emerald-50 text-emerald-800' : 'bg-rose-50 text-rose-700'}`}>
          {message.text}
        </p>
      )}
    </form>
  )
}
