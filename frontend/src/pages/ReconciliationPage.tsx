import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { ManualReviewCase, ReconItem, ReconReport, ReconRun } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { errorText, formatTime } from '../lib/format'
import { formatPaise } from '../lib/money'

function ms(v?: number): string {
  if (v === undefined || v === null) return '—'
  return v < 1000 ? `${v} ms` : `${(v / 1000).toFixed(1)} s`
}

export function ReconciliationPage() {
  const [report, setReport] = useState<ReconReport | null>(null)
  const [runs, setRuns] = useState<ReconRun[]>([])
  const [manual, setManual] = useState<ManualReviewCase[]>([])
  const [selected, setSelected] = useState<string | null>(null)
  const [items, setItems] = useState<ReconItem[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    const [rep, rs, mr] = await Promise.all([api.reconReport(), api.reconRuns(), api.reconManualReview()])
    setReport(rep)
    setRuns(rs)
    setManual(mr)
  }, [])

  useEffect(() => {
    let cancelled = false
    const load = () =>
      Promise.all([api.reconReport(), api.reconRuns(), api.reconManualReview()]).then(
        ([rep, rs, mr]) => {
          if (cancelled) return
          setReport(rep)
          setRuns(rs)
          setManual(mr)
        },
        (err: unknown) => {
          if (!cancelled) setError(errorText(err))
        },
      )
    void load()
    const timer = window.setInterval(() => void load(), 10000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [])

  async function runNow() {
    setBusy(true)
    setError(null)
    try {
      const r = await api.reconRun()
      await refresh()
      await select(r.id)
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function select(id: string) {
    setSelected(id)
    try {
      setItems(await api.reconItems(id))
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-2xl font-bold">Reconciliation</h1>
          <p className="max-w-2xl text-sm text-slate-500">
            The worker asks the bank — by stable reference — what really happened to unfinished payments and finalises each exactly once.
            It never re-sends a transfer, never guesses an unknown outcome, and escalates what it cannot resolve.
          </p>
        </div>
        <button type="button" className="btn-primary" disabled={busy} onClick={() => void runNow()}>
          {busy ? 'Running…' : 'Run reconciliation now'}
        </button>
      </div>
      {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}

      {report && (
        <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-label={`Report, last ${report.hours} hours`}>
          <Stat label="Transactions checked" value={report.checked} hint={`${report.runs} runs in ${report.hours} h`} />
          <Stat label="Resolved" value={report.resolved} hint={`${report.resolvedSuccess} success · ${report.resolvedFailed} failed`} />
          <Stat label="Still pending now" value={report.stillPendingNow} hint={`${report.unresolvedUnknown} unknown outcomes seen`} warn={report.stillPendingNow > 0} />
          <Stat label="Manual review" value={report.manualReviewCases} hint={`${report.escalated} escalated`} warn={report.manualReviewCases > 0} />
          <Stat label="Conflicting records" value={report.conflictingPayments} hint={`${report.postSettlementChecked} cross-checked`} warn={report.conflictingPayments > 0} />
          <Stat label="Ledger inconsistencies" value={report.ledgerInconsistencies} hint="latest run" warn={report.ledgerInconsistencies > 0} />
          <Stat label="Avg recovery time" valueText={ms(report.avgRecoveryMs)} hint="created → resolved" />
          <Stat label="Max recovery time" valueText={ms(report.maxRecoveryMs)} hint="slowest recovery" />
        </section>
      )}

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="card overflow-x-auto" aria-label="Recent runs">
          <h2 className="mb-3 font-semibold">Recent runs</h2>
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase text-slate-500">
              <tr><th className="py-2">Started</th><th>Trigger</th><th>Checked</th><th>Resolved</th><th>Pending</th><th>Duration</th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {runs.length === 0 && <tr><td colSpan={6} className="py-4 text-slate-500">No runs yet.</td></tr>}
              {runs.map((r) => (
                <tr key={r.id} onClick={() => void select(r.id)} className={`cursor-pointer hover:bg-slate-50 ${selected === r.id ? 'bg-brand-50' : ''}`}>
                  <td className="py-2">{formatTime(r.startedAt)}</td>
                  <td className="text-xs">{r.trigger}</td>
                  <td>{r.checked}</td><td>{r.resolved}</td><td>{r.stillPending + r.unknown}</td>
                  <td>{r.status === 'FAILED' ? <StatusBadge status="FAILED" /> : ms(r.durationMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </section>

        <section className="card" aria-label="Run details">
          <h2 className="mb-3 font-semibold">{selected ? 'Run details' : 'Select a run'}</h2>
          {selected && items.length === 0 && <p className="text-sm text-slate-500">This run had nothing to check.</p>}
          <ul className="space-y-2 text-sm">
            {items.map((i) => (
              <li key={i.id} className="rounded-lg border border-slate-100 p-2">
                <div className="flex flex-wrap items-center gap-2">
                  <StatusBadge status={i.outcome} />
                  <span className="text-xs text-slate-500">{i.kind.replace('_', ' ').toLowerCase()}</span>
                  {i.recoveryMs !== undefined && <span className="text-xs text-slate-500">recovered in {ms(i.recoveryMs)}</span>}
                  {i.paymentId && <Link className="text-xs text-brand-700 hover:underline" to={`/payments/${i.paymentId}`}>payment</Link>}
                </div>
                <p className="mt-1 text-slate-700">{i.detail}</p>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <section className="card overflow-x-auto" aria-label="Manual review queue">
        <h2 className="mb-3 font-semibold">Manual review queue</h2>
        {manual.length === 0 ? <p className="text-sm text-slate-500">Nothing needs a human.</p> : (
          <table className="w-full text-left text-sm">
            <thead className="text-xs uppercase text-slate-500">
              <tr><th className="py-2">Payment</th><th>Status</th><th>Amount</th><th>Reason</th><th>Checks</th></tr>
            </thead>
            <tbody className="divide-y divide-slate-100">
              {manual.map((m) => (
                <tr key={m.paymentId}>
                  <td className="py-2"><Link className="font-mono text-xs text-brand-700 hover:underline" to={`/payments/${m.paymentId}`}>{m.payerVpa} → {m.payeeVpa}</Link></td>
                  <td><StatusBadge status={m.status} /></td>
                  <td className="tabular-nums">{formatPaise(m.amountPaise)}</td>
                  <td>{m.reason}</td><td>{m.reconAttempts}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}

function Stat({ label, value, valueText, hint, warn }: { label: string; value?: number; valueText?: string; hint?: string; warn?: boolean }) {
  return (
    <div className="card">
      <div className="label">{label}</div>
      <div className={`text-2xl font-bold tabular-nums ${warn ? 'text-amber-600' : ''}`}>{valueText ?? value}</div>
      {hint && <div className="text-xs text-slate-500">{hint}</div>}
    </div>
  )
}
