import { useCallback, useEffect, useState } from 'react'
import { Link, useParams } from 'react-router-dom'
import { api } from '../api/client'
import type { PaymentDetail } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'
import { errorText, formatTime } from '../lib/format'
import { formatPaise } from '../lib/money'

const SOURCE_LABEL: Record<string, string> = {
  API: 'PaySure',
  BANK_RESPONSE: 'Bank response',
  BANK_CALLBACK: 'Bank callback',
  STATUS_CHECK: 'Status check',
  RECONCILIATION: 'Reconciliation',
}

export function PaymentDetailPage() {
  const { id = '' } = useParams()
  const { user } = useAuth()
  const [detail, setDetail] = useState<PaymentDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [checkResult, setCheckResult] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const load = useCallback(async () => {
    try {
      setDetail(await api.payment(id))
      setError(null)
    } catch (err) {
      setError(errorText(err))
    }
  }, [id])

  const terminal = detail ? ['SUCCESS', 'FAILED'].includes(detail.payment.status) : false

  useEffect(() => {
    let cancelled = false
    const tick = () =>
      api.payment(id).then(
        (d) => {
          if (!cancelled) setDetail(d)
        },
        (err: unknown) => {
          if (!cancelled) setError(errorText(err))
        },
      )
    void tick()
    if (terminal) return () => { cancelled = true }
    const timer = window.setInterval(() => void tick(), 2000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [id, terminal])

  async function checkStatus() {
    setBusy(true)
    try {
      const r = await api.checkStatus(id)
      setCheckResult(`Bank says ${r.bankStatus?.status ?? 'unknown'} → ${r.effect.replace(/_/g, ' ').toLowerCase()}`)
      await load()
    } catch (err) {
      setCheckResult(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function advanceBank() {
    setBusy(true)
    try {
      const r = await api.devBankTick(600)
      setCheckResult(`Bank clock advanced: ${r.transfersResolved} resolved, ${r.callbacksDelivered} callback(s) delivered`)
      await load()
    } catch (err) {
      setCheckResult(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  if (error && !detail) return <p role="alert" className="card text-rose-700">{error}</p>
  if (!detail) return <div className="card text-sm text-slate-500">Loading…</div>

  const p = detail.payment
  const bank = detail.simulatedBank
  const attempt = detail.attempts[detail.attempts.length - 1]
  const isPayer = p.direction === 'SENT'
  const isAdmin = user?.role === 'ADMIN_DEMO'
  // Only an ambiguous reply (timeout / network error) can hide a committed outcome. ACCEPTED is a normal
  // asynchronous flow where the outcome is expected to arrive later by callback.
  const mismatch = !!bank && bank.committedStatus !== 'PROCESSING' &&
    (attempt?.responseKind === 'TIMEOUT' || attempt?.responseKind === 'NETWORK_ERROR')

  return (
    <div className="space-y-6">
      <Link to="/history" className="text-sm text-brand-700 hover:underline">← Payment history</Link>

      <section className="card flex flex-wrap items-start justify-between gap-4">
        <div>
          <div className="text-sm text-slate-500">{isPayer ? 'Paid to' : 'Received from'}</div>
          <div className="font-mono text-lg">{isPayer ? p.payeeVpa : p.payerVpa}</div>
          {p.note && <div className="mt-1 text-sm text-slate-600">“{p.note}”</div>}
          <div className="mt-2 font-mono text-xs text-slate-400">Payment {p.id}{p.bankReference && ` · Bank ref ${p.bankReference}`}</div>
        </div>
        <div className="text-right">
          <div className="text-3xl font-bold tabular-nums">{formatPaise(p.amountPaise)}</div>
          <div className="mt-1 flex items-center justify-end gap-2">
            <StatusBadge status={p.status} />
            <span className="text-xs text-slate-500">funds {p.holdStatus.toLowerCase()}</span>
          </div>
        </div>
        <p className={`w-full rounded-lg px-3 py-2 text-sm ${p.status === 'PENDING' ? 'bg-amber-50 text-amber-900' : p.status === 'FAILED' ? 'bg-rose-50 text-rose-800' : 'bg-slate-50 text-slate-700'}`}>
          {p.statusMessage}{p.failureReason && ` (${p.failureReason})`}
        </p>
        {!terminal && (isPayer || isAdmin) && (
          <div className="flex flex-wrap gap-2">
            <button type="button" className="btn-primary" disabled={busy} onClick={() => void checkStatus()}>Check status with bank</button>
            {isAdmin && <button type="button" className="btn-secondary" disabled={busy} onClick={() => void advanceBank()}>Fast-forward simulated bank</button>}
          </div>
        )}
        {checkResult && <p role="status" className="w-full text-sm text-slate-600">{checkResult}</p>}
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        <section className="card" aria-label="Status timeline">
          <h2 className="mb-4 font-semibold">Status timeline</h2>
          <ol className="relative space-y-4 border-l border-slate-200 pl-6">
            {detail.history.map((h, i) => (
              <li key={i} className="relative">
                <span className="absolute -left-[31px] top-1 h-3 w-3 rounded-full bg-brand-500 ring-4 ring-white" />
                <div className="flex flex-wrap items-center gap-2">
                  <StatusBadge status={h.toStatus} />
                  <span className="text-xs text-slate-500">{SOURCE_LABEL[h.source] ?? h.source} · {formatTime(h.at)}</span>
                </div>
                <p className="mt-1 text-sm text-slate-700">{h.reason}</p>
              </li>
            ))}
          </ol>
        </section>

        <section className="card space-y-4" aria-label="Bank processing">
          <h2 className="font-semibold">Bank processing</h2>
          {detail.attempts.length === 0 && <p className="text-sm text-slate-500">Never sent to the bank.</p>}
          {attempt && (
            <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
              <dt className="text-slate-500">Submissions</dt><dd>{detail.attempts.length} (never re-sent)</dd>
              <dt className="text-slate-500">Response received</dt><dd className="font-semibold">{attempt.responseKind ?? '—'}</dd>
              <dt className="text-slate-500">Callbacks applied</dt><dd>{attempt.callbacksReceived}</dd>
              <dt className="text-slate-500">Duplicates / stale ignored</dt><dd>{attempt.callbacksIgnored}</dd>
            </dl>
          )}
          {bank && (
            <div className="rounded-xl border border-dashed border-slate-300 p-4">
              <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">Simulated bank's own record (sandbox insight)</div>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-sm">
                <dt className="text-slate-500">Scenario</dt><dd className="font-mono text-xs">{bank.scenario}</dd>
                <dt className="text-slate-500">Actually committed</dt><dd><StatusBadge status={bank.committedStatus} /></dd>
                <dt className="text-slate-500">Told PaySure</dt><dd className="font-semibold">{bank.responseDelivered}</dd>
                <dt className="text-slate-500">Committed at</dt><dd>{formatTime(bank.committedAt)}</dd>
              </dl>
              {mismatch && (
                <p className="mt-3 rounded-lg bg-amber-50 px-3 py-2 text-xs text-amber-900">
                  What the bank did and what it told PaySure differ — exactly why PaySure keeps the payment PENDING and verifies instead of guessing.
                </p>
              )}
              {bank.callbacks.length > 0 && (
                <table className="mt-3 w-full text-left text-xs">
                  <thead className="text-slate-500"><tr><th>Seq</th><th>Reports</th><th>Due</th><th>Delivered</th></tr></thead>
                  <tbody>
                    {bank.callbacks.map((c) => (
                      <tr key={c.id}>
                        <td>{c.sequence}</td><td>{c.reportedStatus}</td>
                        <td>{formatTime(c.deliverAt)}</td><td>{c.deliveredAt ? formatTime(c.deliveredAt) : 'not yet'}</td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              )}
            </div>
          )}
        </section>
      </div>
    </div>
  )
}
