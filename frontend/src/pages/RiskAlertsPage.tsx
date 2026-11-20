import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { RiskAlert, RiskAssessment, RiskConfig } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'
import { useOwnVpas } from '../hooks/useOwnVpas'
import { errorText, formatTime } from '../lib/format'
import { formatPaise, parseRupeesToPaise } from '../lib/money'

const METER: Record<string, string> = { LOW: 'bg-emerald-500', MEDIUM: 'bg-amber-500', HIGH: 'bg-rose-500' }

export function RiskAlertsPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN_DEMO'
  const { vpas } = useOwnVpas()
  const [config, setConfig] = useState<RiskConfig | null>(null)
  const [alerts, setAlerts] = useState<RiskAlert[] | null>(null)
  const [scope, setScope] = useState<'mine' | 'all'>('mine')
  const [filter, setFilter] = useState<'ALL' | 'OPEN'>('ALL')
  const [payer, setPayer] = useState('')
  const [payee, setPayee] = useState('')
  const [amount, setAmount] = useState('')
  const [assessment, setAssessment] = useState<RiskAssessment | null>(null)
  const [error, setError] = useState<string | null>(null)

  const load = useCallback(
    () =>
      scope === 'all' && isAdmin
        ? api.adminRiskAlerts(filter === 'OPEN' ? 'OPEN' : undefined)
        : api.riskAlerts().then((list) => (filter === 'OPEN' ? list.filter((a) => a.status === 'OPEN') : list)),
    [scope, filter, isAdmin],
  )

  useEffect(() => {
    let cancelled = false
    api.riskConfig().then(
      (c) => {
        if (!cancelled) setConfig(c)
      },
      () => undefined,
    )
    const tick = () =>
      load().then(
        (list) => {
          if (!cancelled) setAlerts(list)
        },
        (err: unknown) => {
          if (!cancelled) setError(errorText(err))
        },
      )
    void tick()
    const timer = window.setInterval(() => void tick(), 5000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [load])

  async function assess(e: FormEvent) {
    e.preventDefault()
    setError(null)
    setAssessment(null)
    const paise = parseRupeesToPaise(amount)
    const from = payer || vpas[0]?.vpa
    if (paise === null || !from) {
      setError('Choose a VPA and enter a positive amount with at most two decimals.')
      return
    }
    try {
      setAssessment(await api.riskAssess({ payerVpa: from, payeeVpa: payee.trim(), amountPaise: paise }))
    } catch (err) {
      setError(errorText(err))
    }
  }

  async function review(id: string, status: 'ACKNOWLEDGED' | 'DISMISSED') {
    try {
      await api.reviewRiskAlert(id, status)
      setAlerts(await load())
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Risk alerts</h1>
        <p role="note" className="mt-1 max-w-3xl rounded-lg bg-amber-50 px-3 py-2 text-sm text-amber-900">
          {config?.disclaimer ?? 'Educational simulation: transparent rules, not real fraud determinations.'}
          {config && <> Mode: <strong>{config.mode}</strong> {config.mode === 'ALERT' ? '(alerts only — payments are never refused).' : `(payments scoring ${config.blockScore}+ are refused).`}</>}
        </p>
      </div>
      {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}

      <div className="grid gap-6 lg:grid-cols-3">
        <form onSubmit={(e) => void assess(e)} className="card space-y-3 lg:col-span-1" aria-label="Risk simulator">
          <h2 className="font-semibold">Risk simulator</h2>
          <p className="text-xs text-slate-500">See how a payment would score right now. Nothing is sent or recorded.</p>
          <div>
            <label className="label" htmlFor="rk-from">From</label>
            <select id="rk-from" className="input" value={payer || vpas[0]?.vpa || ''} onChange={(e) => setPayer(e.target.value)}>
              {vpas.map((v) => <option key={v.vpa} value={v.vpa}>{v.vpa}</option>)}
            </select>
          </div>
          <div>
            <label className="label" htmlFor="rk-to">To</label>
            <input id="rk-to" className="input font-mono" value={payee} onChange={(e) => setPayee(e.target.value)} placeholder="rahul@paysure" />
          </div>
          <div>
            <label className="label" htmlFor="rk-amount">Amount (₹)</label>
            <input id="rk-amount" className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="5000" />
          </div>
          <button type="submit" className="btn-primary w-full" disabled={!payee.trim() || !amount}>Assess</button>
          {assessment && <AssessmentView a={assessment} />}
          {config && (
            <details className="text-xs text-slate-600">
              <summary className="cursor-pointer font-medium">Rules and weights</summary>
              <ul className="mt-2 space-y-1">
                {config.rules.map((r) => (
                  <li key={r.code}>
                    <span className="font-mono">{r.code}</span> +{r.points}
                    {r.threshold !== undefined && <> · ≥ {r.threshold}{r.windowSeconds ? ` in ${r.windowSeconds}s` : ''}</>}
                    {r.thresholdPaise !== undefined && <> · ≥ {formatPaise(r.thresholdPaise)}</>}
                  </li>
                ))}
                <li>Alert at {config.alertScore}, high at {config.highScore}, block suggested at {config.blockScore}.</li>
              </ul>
            </details>
          )}
        </form>

        <section className="space-y-3 lg:col-span-2" aria-label="Alerts">
          <div className="flex flex-wrap items-center justify-between gap-2">
            <h2 className="font-semibold">{scope === 'all' ? 'All users’ alerts' : 'Your alerts'}</h2>
            <div className="flex gap-1 text-xs">
              {isAdmin && (
                <button type="button" aria-pressed={scope === 'all'} onClick={() => setScope(scope === 'all' ? 'mine' : 'all')}
                  className={`rounded-full px-3 py-1 font-semibold ${scope === 'all' ? 'bg-brand-600 text-white' : 'bg-white ring-1 ring-slate-200'}`}>
                  All users
                </button>
              )}
              {(['ALL', 'OPEN'] as const).map((f) => (
                <button key={f} type="button" aria-pressed={filter === f} onClick={() => setFilter(f)}
                  className={`rounded-full px-3 py-1 font-semibold ${filter === f ? 'bg-brand-600 text-white' : 'bg-white ring-1 ring-slate-200'}`}>
                  {f === 'ALL' ? 'All' : 'Open'}
                </button>
              ))}
            </div>
          </div>
          {alerts === null && <div className="card text-sm text-slate-500">Loading…</div>}
          {alerts?.length === 0 && <div className="card text-sm text-slate-600">No risk alerts. Try several quick payments to the same person to see one.</div>}
          {alerts?.map((a) => (
            <article key={a.id} className="card space-y-2" aria-label={`Alert ${a.score}`}>
              <div className="flex flex-wrap items-center justify-between gap-2">
                <div className="flex items-center gap-2">
                  <span className="text-2xl font-bold tabular-nums">{a.score}</span>
                  <StatusBadge status={a.level} />
                  <StatusBadge status={a.status} />
                  {a.blocked && <span className="rounded-full bg-rose-600 px-2 py-0.5 text-xs font-semibold text-white">REFUSED</span>}
                </div>
                <div className="text-xs text-slate-500">{formatTime(a.createdAt)}</div>
              </div>
              <div className="text-sm">
                <span className="font-mono">{a.payerVpa}</span> → <span className="font-mono">{a.payeeVpa}</span> · {formatPaise(a.amountPaise)}
                {a.paymentId && <> · <Link className="text-brand-700 hover:underline" to={`/payments/${a.paymentId}`}>payment</Link></>}
              </div>
              <ul className="list-disc space-y-0.5 pl-5 text-sm text-slate-700">
                {a.explanation.slice(1).map((line, i) => <li key={i}>{line}</li>)}
              </ul>
              <p className="rounded-lg bg-slate-50 px-3 py-2 text-xs text-slate-600"><strong>Suggested:</strong> {a.suggestedAction}</p>
              {isAdmin && a.status === 'OPEN' && (
                <div className="flex gap-2">
                  <button type="button" className="btn-secondary px-3 py-1" onClick={() => void review(a.id, 'ACKNOWLEDGED')}>Acknowledge</button>
                  <button type="button" className="btn-secondary px-3 py-1" onClick={() => void review(a.id, 'DISMISSED')}>Dismiss</button>
                </div>
              )}
            </article>
          ))}
        </section>
      </div>
    </div>
  )
}

function AssessmentView({ a }: { a: RiskAssessment }) {
  return (
    <div className="space-y-2 rounded-xl border border-slate-200 p-3" role="status" aria-label="Assessment">
      <div className="flex items-center justify-between">
        <div className="text-3xl font-bold tabular-nums">{a.score}<span className="text-sm font-normal text-slate-500">/100</span></div>
        <StatusBadge status={a.level} />
      </div>
      <div className="h-2 rounded-full bg-slate-100"><div className={`h-2 rounded-full ${METER[a.level]}`} style={{ width: `${a.score}%` }} /></div>
      <ul className="list-disc space-y-0.5 pl-5 text-sm">
        {a.explanation.slice(1).map((l, i) => <li key={i}>{l}</li>)}
      </ul>
      <p className="text-xs text-slate-600">{a.suggestedAction}</p>
      {a.wouldBlock && <p className="rounded bg-rose-50 px-2 py-1 text-xs text-rose-700">Enforcement is on: this payment would be refused.</p>}
    </div>
  )
}
