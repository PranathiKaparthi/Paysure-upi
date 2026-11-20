import { useEffect, useState, type FormEvent } from 'react'
import { Link } from 'react-router-dom'
import { ApiError, api } from '../api/client'
import type { Payment, ResolvedVpa, Scenario } from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'
import { useOwnVpas } from '../hooks/useOwnVpas'
import { errorText, newIdempotencyKey } from '../lib/format'
import { formatPaise, parseRupeesToPaise } from '../lib/money'

export const SCENARIOS: { value: Scenario; label: string; hint: string }[] = [
  { value: 'NORMAL_SUCCESS', label: 'Normal success', hint: 'Bank commits and confirms immediately.' },
  { value: 'INSUFFICIENT_FUNDS', label: 'Declined (insufficient funds)', hint: 'Remitter bank declines; nothing is debited.' },
  { value: 'BANK_TIMEOUT', label: 'Bank timeout (not committed)', hint: 'No answer in time; the bank did not debit. Verify → FAILED.' },
  { value: 'TIMEOUT_AFTER_COMMIT', label: 'Lost acknowledgement', hint: 'Bank committed but the reply was lost. Verify → SUCCESS.' },
  { value: 'BANK_UNAVAILABLE', label: 'Bank unavailable', hint: 'Bank refuses the request outright. Definitive failure.' },
  { value: 'NETWORK_FAILURE', label: 'Network failure', hint: 'Request never reached the bank. Verify → no record → FAILED.' },
  { value: 'DELAYED_SUCCESS', label: 'Delayed success', hint: 'Accepted now; SUCCESS callback after the delay.' },
  { value: 'DELAYED_FAILURE', label: 'Delayed failure', hint: 'Accepted now; FAILED callback after the delay.' },
  { value: 'DUPLICATE_CALLBACK', label: 'Duplicate callbacks', hint: 'SUCCESS callback delivered N times; posted once.' },
  { value: 'OUT_OF_ORDER_CALLBACK', label: 'Out-of-order callbacks', hint: 'Final status arrives before a stale one.' },
]

export function SendPaymentPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN_DEMO'
  const { vpas, reload } = useOwnVpas()
  const [payerVpa, setPayerVpa] = useState('')
  const [payeeVpa, setPayeeVpa] = useState('')
  const [payee, setPayee] = useState<ResolvedVpa | null>(null)
  const [payeeError, setPayeeError] = useState<string | null>(null)
  const [amount, setAmount] = useState('')
  const [note, setNote] = useState('')
  const [scenario, setScenario] = useState<Scenario | ''>('')
  const [delay, setDelay] = useState(10)
  const [duplicates, setDuplicates] = useState(3)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)
  const [result, setResult] = useState<{ payment: Payment; replayed: boolean } | null>(null)
  // The key for the payment currently being attempted. Kept across a network-error retry, cleared once the
  // server has given a definitive answer, so "Retry" can never create a second payment.
  const [pendingKey, setPendingKey] = useState<string | null>(null)
  const [canRetry, setCanRetry] = useState(false)

  const from = payerVpa || vpas[0]?.vpa || ''
  const fromInfo = vpas.find((v) => v.vpa === from)

  // Look the payee up shortly after typing stops (and on blur), so the registered name is shown before paying.
  useEffect(() => {
    const candidate = payeeVpa.trim()
    if (!/^[a-z0-9][a-z0-9._-]{2,29}@[a-z]{3,20}$/i.test(candidate)) return
    let cancelled = false
    const timer = window.setTimeout(() => {
      api.resolveVpa(candidate).then(
        (r) => {
          if (!cancelled) {
            setPayee(r)
            setPayeeError(null)
          }
        },
        (err: unknown) => {
          if (!cancelled) setPayeeError(errorText(err))
        },
      )
    }, 400)
    return () => {
      cancelled = true
      window.clearTimeout(timer)
    }
  }, [payeeVpa])

  async function submit(e?: FormEvent, retryKey?: string) {
    e?.preventDefault()
    setError(null)
    const paise = parseRupeesToPaise(amount)
    if (paise === null) {
      setError('Enter a positive amount in rupees with at most two decimals.')
      return
    }
    const key = retryKey ?? newIdempotencyKey()
    setPendingKey(key)
    setBusy(true)
    try {
      const res = await api.createPayment(
        {
          payerVpa: from,
          payeeVpa: payeeVpa.trim(),
          amountPaise: paise,
          note: note || undefined,
          simulation: isAdmin && scenario ? { scenario, delaySeconds: delay, duplicateCallbacks: duplicates } : undefined,
        },
        key,
      )
      setResult(res)
      setPendingKey(null)
      setCanRetry(false)
      reload()
    } catch (err) {
      if (err instanceof ApiError && err.status === 0) {
        // Unknown outcome: the request may or may not have reached the server. Offer a SAFE retry.
        setCanRetry(true)
        setError('We could not reach PaySure. Your payment may or may not have been created — use "Retry safely", which reuses the same Idempotency-Key.')
      } else {
        setPendingKey(null)
        setCanRetry(false)
        setError(errorText(err))
      }
    } finally {
      setBusy(false)
    }
  }

  const scenarioHint = SCENARIOS.find((s) => s.value === scenario)?.hint

  return (
    <div className="grid gap-6 lg:grid-cols-5">
      <form onSubmit={(e) => void submit(e)} className="card space-y-4 lg:col-span-3" aria-label="Send payment">
        <h1 className="text-xl font-bold">Send money</h1>
        <div>
          <label className="label" htmlFor="from">From</label>
          <select id="from" className="input" value={from} onChange={(e) => setPayerVpa(e.target.value)}>
            {vpas.length === 0 && <option value="">No active VPA — open an account first</option>}
            {vpas.map((v) => (
              <option key={v.vpa} value={v.vpa}>
                {v.vpa} · {v.accountName} · available {formatPaise(v.availablePaise)}
              </option>
            ))}
          </select>
        </div>
        <div>
          <label className="label" htmlFor="to">To (payee VPA)</label>
          <input id="to" className="input font-mono" placeholder="rahul@paysure" value={payeeVpa}
            onChange={(e) => { setPayeeVpa(e.target.value); setPayee(null); setPayeeError(null) }} />
          {payee && <p className="mt-1 text-sm text-emerald-700">✓ {payee.payeeName}</p>}
          {payeeError && <p className="mt-1 text-sm text-rose-700">{payeeError}</p>}
        </div>
        <div className="grid gap-4 sm:grid-cols-2">
          <div>
            <label className="label" htmlFor="amount">Amount (₹)</label>
            <input id="amount" className="input text-lg font-semibold" inputMode="decimal" placeholder="250.00" value={amount}
              onChange={(e) => setAmount(e.target.value)} />
          </div>
          <div>
            <label className="label" htmlFor="note">Note</label>
            <input id="note" className="input" maxLength={100} placeholder="Dinner" value={note} onChange={(e) => setNote(e.target.value)} />
          </div>
        </div>

        {isAdmin && (
          <fieldset className="space-y-3 rounded-xl border border-dashed border-amber-300 bg-amber-50/50 p-4">
            <legend className="px-1 text-xs font-semibold uppercase tracking-wide text-amber-800">Bank simulation (demo admin)</legend>
            <select aria-label="Bank scenario" className="input" value={scenario} onChange={(e) => setScenario(e.target.value as Scenario | '')}>
              <option value="">Simulator default</option>
              {SCENARIOS.map((s) => <option key={s.value} value={s.value}>{s.label}</option>)}
            </select>
            {scenarioHint && <p className="text-xs text-amber-900">{scenarioHint}</p>}
            <div className="grid grid-cols-2 gap-3">
              <label className="text-xs text-slate-600">Delay (s)
                <input type="number" min={0} max={600} className="input mt-1" value={delay} onChange={(e) => setDelay(Number(e.target.value))} />
              </label>
              <label className="text-xs text-slate-600">Duplicate callbacks
                <input type="number" min={1} max={10} className="input mt-1" value={duplicates} onChange={(e) => setDuplicates(Number(e.target.value))} />
              </label>
            </div>
          </fieldset>
        )}

        {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}
        <div className="flex flex-wrap items-center gap-3">
          <button type="submit" className="btn-primary" disabled={busy || !from || !payeeVpa.trim() || !amount}>
            {busy ? 'Sending…' : `Pay ${parseRupeesToPaise(amount) ? formatPaise(parseRupeesToPaise(amount)!) : ''}`}
          </button>
          {canRetry && pendingKey && (
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => void submit(undefined, pendingKey)}>
              Retry safely
            </button>
          )}
          {fromInfo && <span className="text-xs text-slate-500">Available: {formatPaise(fromInfo.availablePaise)}</span>}
        </div>
        {pendingKey && <p className="font-mono text-xs text-slate-400">Idempotency-Key: {pendingKey}</p>}
      </form>

      <aside className="space-y-4 lg:col-span-2">
        {result ? <ResultCard {...result} /> : (
          <div className="card text-sm text-slate-600">
            <h2 className="mb-2 font-semibold text-slate-900">How PaySure keeps this safe</h2>
            <ul className="list-disc space-y-1 pl-5">
              <li>Each payment carries a unique <strong>Idempotency-Key</strong>; retries reuse it, so a double click or a flaky network cannot pay twice.</li>
              <li>Your funds are <strong>held</strong> before the bank is called, so they cannot be spent twice while the outcome is unknown.</li>
              <li>If the bank times out, the payment stays <strong>PENDING</strong> and is verified with the bank — never blindly re-sent.</li>
            </ul>
          </div>
        )}
      </aside>
    </div>
  )
}

function ResultCard({ payment, replayed }: { payment: Payment; replayed: boolean }) {
  return (
    <div className="card space-y-3" role="status" aria-label="Payment result">
      <div className="flex items-center justify-between">
        <div className="text-2xl font-bold tabular-nums">{formatPaise(payment.amountPaise)}</div>
        <StatusBadge status={payment.status} />
      </div>
      <div className="text-sm text-slate-600">to <span className="font-mono">{payment.payeeVpa}</span></div>
      <p className="text-sm">{payment.statusMessage}</p>
      {payment.failureReason && <p className="text-sm text-rose-700">{payment.failureReason}</p>}
      {replayed && (
        <p className="rounded-lg bg-sky-50 px-3 py-2 text-xs text-sky-800">
          Idempotent replay: this key was already used, so the original payment was returned and nothing was charged again.
        </p>
      )}
      <Link className="btn-secondary w-full" to={`/payments/${payment.id}`}>View details & timeline</Link>
    </div>
  )
}
