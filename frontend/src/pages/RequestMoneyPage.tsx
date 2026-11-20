import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { CollectRequest } from '../api/types'
import { StatusBadge } from '../components/StatusBadge'
import { useOwnVpas } from '../hooks/useOwnVpas'
import { errorText, formatTime } from '../lib/format'
import { formatPaise, parseRupeesToPaise } from '../lib/money'

export function RequestMoneyPage() {
  const { vpas } = useOwnVpas()
  const [toVpa, setToVpa] = useState('')
  const [payerVpa, setPayerVpa] = useState('')
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [message, setMessage] = useState<{ ok: boolean; text: string } | null>(null)
  const [incoming, setIncoming] = useState<CollectRequest[]>([])
  const [outgoing, setOutgoing] = useState<CollectRequest[]>([])

  const refresh = useCallback(async () => {
    const [inc, out] = await Promise.all([api.listCollect('incoming'), api.listCollect('outgoing')])
    setIncoming(inc)
    setOutgoing(out)
  }, [])

  useEffect(() => {
    let cancelled = false
    Promise.all([api.listCollect('incoming'), api.listCollect('outgoing')]).then(
      ([inc, out]) => {
        if (!cancelled) {
          setIncoming(inc)
          setOutgoing(out)
        }
      },
      (err: unknown) => {
        if (!cancelled) setMessage({ ok: false, text: errorText(err) })
      },
    )
    return () => {
      cancelled = true
    }
  }, [])

  const requester = toVpa || vpas[0]?.vpa || ''

  async function create(e: FormEvent) {
    e.preventDefault()
    const paise = parseRupeesToPaise(amount)
    if (paise === null) return setMessage({ ok: false, text: 'Enter a positive rupee amount with at most two decimals.' })
    try {
      await api.createCollect({ requesterVpa: requester, payerVpa: payerVpa.trim(), amountPaise: paise, note: note || undefined })
      setMessage({ ok: true, text: `Requested ${formatPaise(paise)} from ${payerVpa.trim()}.` })
      setAmount('')
      setNote('')
      await refresh()
    } catch (err) {
      setMessage({ ok: false, text: errorText(err) })
    }
  }

  async function act(action: () => Promise<unknown>, success: string) {
    try {
      await action()
      setMessage({ ok: true, text: success })
      await refresh()
    } catch (err) {
      setMessage({ ok: false, text: errorText(err) })
    }
  }

  return (
    <div className="grid gap-6 lg:grid-cols-5">
      <form onSubmit={(e) => void create(e)} className="card space-y-4 lg:col-span-2" aria-label="Request money">
        <h1 className="text-xl font-bold">Request money</h1>
        <div>
          <label className="label" htmlFor="req-to">Receive into</label>
          <select id="req-to" className="input" value={requester} onChange={(e) => setToVpa(e.target.value)}>
            {vpas.map((v) => <option key={v.vpa} value={v.vpa}>{v.vpa} · {v.accountName}</option>)}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="req-from">From (payer VPA)</label>
          <input id="req-from" className="input font-mono" placeholder="rahul@paysure" value={payerVpa} onChange={(e) => setPayerVpa(e.target.value)} />
        </div>
        <div className="grid grid-cols-2 gap-3">
          <div>
            <label className="label" htmlFor="req-amount">Amount (₹)</label>
            <input id="req-amount" className="input" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} placeholder="500" />
          </div>
          <div>
            <label className="label" htmlFor="req-note">Note</label>
            <input id="req-note" className="input" maxLength={100} value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
        </div>
        <button type="submit" className="btn-primary w-full" disabled={!requester || !payerVpa.trim() || !amount}>Send request</button>
        {message && (
          <p role={message.ok ? 'status' : 'alert'} className={`rounded-lg px-3 py-2 text-sm ${message.ok ? 'bg-emerald-50 text-emerald-800' : 'bg-rose-50 text-rose-700'}`}>
            {message.text}
          </p>
        )}
      </form>

      <div className="space-y-6 lg:col-span-3">
        <CollectList title="Requests to you" rows={incoming} empty="Nobody has asked you for money."
          actions={(r) => r.status === 'PENDING' && (
            <>
              <button type="button" className="btn-primary px-3 py-1" onClick={() => void act(() => api.approveCollect(r.id), `Paid ${formatPaise(r.amountPaise)} to ${r.requesterVpa}.`)}>Pay</button>
              <button type="button" className="btn-secondary px-3 py-1" onClick={() => void act(() => api.declineCollect(r.id), 'Request declined.')}>Decline</button>
            </>
          )} />
        <CollectList title="Your requests" rows={outgoing} empty="You have not requested money yet."
          actions={(r) => r.status === 'PENDING' && (
            <button type="button" className="btn-secondary px-3 py-1" onClick={() => void act(() => api.cancelCollect(r.id), 'Request cancelled.')}>Cancel</button>
          )} />
      </div>
    </div>
  )
}

function CollectList({ title, rows, empty, actions }: {
  title: string
  rows: CollectRequest[]
  empty: string
  actions: (r: CollectRequest) => React.ReactNode
}) {
  return (
    <section className="card" aria-label={title}>
      <h2 className="mb-3 font-semibold">{title}</h2>
      {rows.length === 0 && <p className="text-sm text-slate-500">{empty}</p>}
      <ul className="divide-y divide-slate-100">
        {rows.map((r) => (
          <li key={r.id} className="flex flex-wrap items-center justify-between gap-3 py-3">
            <div>
              <div className="font-mono text-sm">{r.myRole === 'PAYER' ? r.requesterVpa : r.payerVpa}</div>
              <div className="text-xs text-slate-500">{r.note ? `“${r.note}” · ` : ''}{formatTime(r.createdAt)}</div>
            </div>
            <div className="flex items-center gap-2">
              <span className="font-semibold tabular-nums">{formatPaise(r.amountPaise)}</span>
              <StatusBadge status={r.status} />
              {actions(r)}
              {r.paymentId && <Link className="text-xs text-brand-700 hover:underline" to={`/payments/${r.paymentId}`}>payment</Link>}
            </div>
          </li>
        ))}
      </ul>
    </section>
  )
}
