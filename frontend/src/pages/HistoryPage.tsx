import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { Payment, PaymentStatus } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { errorText, formatTime } from '../lib/format'
import { formatPaise } from '../lib/money'

const FILTERS: (PaymentStatus | 'ALL')[] = ['ALL', 'SUCCESS', 'PENDING', 'PROCESSING', 'FAILED']

export function HistoryPage() {
  const [filter, setFilter] = useState<PaymentStatus | 'ALL'>('ALL')
  const [rows, setRows] = useState<Payment[] | null>(null)
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const load = () =>
      api.listPayments(filter === 'ALL' ? undefined : filter).then(
        (list) => {
          if (!cancelled) {
            setRows(list)
            setError(null)
          }
        },
        (err: unknown) => {
          if (!cancelled) setError(errorText(err))
        },
      )
    void load()
    // Periodic refresh so PENDING payments visibly settle as the bank answers.
    const timer = window.setInterval(() => void load(), 5000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [filter])

  return (
    <div className="space-y-4">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h1 className="text-xl font-bold">Payment history</h1>
        <div className="flex flex-wrap gap-1" role="group" aria-label="Filter by status">
          {FILTERS.map((f) => (
            <button key={f} type="button" aria-pressed={filter === f} onClick={() => setFilter(f)}
              className={`rounded-full px-3 py-1 text-xs font-semibold ${filter === f ? 'bg-brand-600 text-white' : 'bg-white text-slate-600 ring-1 ring-slate-200'}`}>
              {f}
            </button>
          ))}
        </div>
      </div>
      {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}
      <div className="card overflow-x-auto p-0">
        <table className="w-full text-left text-sm">
          <thead className="border-b border-slate-200 bg-slate-50 text-xs uppercase text-slate-500">
            <tr>
              <th className="px-4 py-3">When</th>
              <th className="px-4">Counterparty</th>
              <th className="px-4">Note</th>
              <th className="px-4 text-right">Amount</th>
              <th className="px-4">Status</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {rows === null && <tr><td className="px-4 py-6 text-slate-500" colSpan={5}>Loading…</td></tr>}
            {rows?.length === 0 && <tr><td className="px-4 py-6 text-slate-500" colSpan={5}>No payments yet.</td></tr>}
            {rows?.map((p) => {
              const sent = p.direction === 'SENT'
              return (
                <tr key={p.id} className="hover:bg-slate-50">
                  <td className="whitespace-nowrap px-4 py-3 text-slate-500">{formatTime(p.createdAt)}</td>
                  <td className="px-4">
                    <Link to={`/payments/${p.id}`} className="font-mono text-brand-700 hover:underline">
                      {sent ? `→ ${p.payeeVpa}` : `← ${p.payerVpa}`}
                    </Link>
                  </td>
                  <td className="px-4 text-slate-600">{p.note ?? ''}</td>
                  <td className={`whitespace-nowrap px-4 text-right font-semibold tabular-nums ${sent ? 'text-slate-900' : 'text-emerald-700'}`}>
                    {sent ? '−' : '+'}{formatPaise(p.amountPaise)}
                  </td>
                  <td className="px-4"><StatusBadge status={p.status} /></td>
                </tr>
              )
            })}
          </tbody>
        </table>
      </div>
    </div>
  )
}
