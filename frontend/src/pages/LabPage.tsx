import { useCallback, useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import type { LabAction, LabBalances, LabRunListItem, LabScenario, LabView, ReliabilityScore, ScoreSnapshot } from '../api/types'
import { ScoreCard } from '../components/ScoreCard'
import { StatusBadge } from '../components/StatusBadge'
import { errorText, formatTime } from '../lib/format'
import { formatPaise, parseRupeesToPaise } from '../lib/money'

const ACTOR_STYLE: Record<string, string> = {
  CLIENT: 'bg-slate-100 text-slate-700',
  PAYSURE: 'bg-brand-50 text-brand-700',
  BANK: 'bg-violet-50 text-violet-700',
  RECONCILER: 'bg-emerald-50 text-emerald-700',
}

const ACTION_LABEL: Record<LabAction, string> = {
  RECONCILE: 'Run reconciliation',
  FAST_FORWARD: 'Fast-forward bank clock',
  RESTORE_STATUS_API: 'Restore bank status API',
}

export function LabPage() {
  const [scenarios, setScenarios] = useState<LabScenario[]>([])
  const [code, setCode] = useState('LOST_ACKNOWLEDGEMENT')
  const [amount, setAmount] = useState('500')
  const [delay, setDelay] = useState<number | ''>('')
  const [dups, setDups] = useState<number | ''>('')
  const [view, setView] = useState<LabView | null>(null)
  const [runs, setRuns] = useState<LabRunListItem[]>([])
  const [score, setScore] = useState<ReliabilityScore | null>(null)
  const [history, setHistory] = useState<ScoreSnapshot[]>([])
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const scenario = scenarios.find((s) => s.code === code)

  const refreshSide = useCallback(async () => {
    const [r, s, h] = await Promise.all([api.labRuns(), api.reliability(), api.reliabilityHistory()])
    setRuns(r)
    setScore(s)
    setHistory(h)
  }, [])

  useEffect(() => {
    let cancelled = false
    Promise.all([api.labScenarios(), api.labRuns(), api.reliability(), api.reliabilityHistory()]).then(
      ([sc, r, s, h]) => {
        if (cancelled) return
        setScenarios(sc)
        setRuns(r)
        setScore(s)
        setHistory(h)
      },
      (err: unknown) => {
        if (!cancelled) setError(errorText(err))
      },
    )
    return () => {
      cancelled = true
    }
  }, [])

  // While a run is unfinished the background reconciler may heal it; keep the page current.
  const runId = view?.id
  const finished = view?.finished ?? true
  useEffect(() => {
    if (!runId || finished) return
    const timer = window.setInterval(() => {
      api.labRun(runId).then(setView, () => undefined)
    }, 3000)
    return () => window.clearInterval(timer)
  }, [runId, finished])

  async function start() {
    const paise = parseRupeesToPaise(amount)
    if (paise === null || paise > 500_000) {
      setError('Enter an amount between ₹0.01 and ₹5,000.00.')
      return
    }
    setBusy(true)
    setError(null)
    try {
      setView(await api.labStart({
        scenario: code,
        amountPaise: paise,
        delaySeconds: delay === '' ? undefined : delay,
        duplicateCallbacks: dups === '' ? undefined : dups,
      }))
      await refreshSide()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function act(action: LabAction, repeat = 1) {
    if (!view) return
    setBusy(true)
    setError(null)
    try {
      setView(await api.labAct(view.id, action, repeat))
      await refreshSide()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function open(id: string) {
    try {
      setView(await api.labRun(id))
    } catch (err) {
      setError(errorText(err))
    }
  }

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Payment Reliability Lab</h1>
        <p className="text-sm text-slate-500">
          Inject a failure into the <strong>simulated</strong> bank and watch PaySure detect it and recover — without ever re-sending the transfer.
          Everything stays inside the sandbox.
        </p>
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <section className="card space-y-4 lg:col-span-1" aria-label="Start simulation">
          <div>
            <label className="label" htmlFor="scenario">Failure scenario</label>
            <select id="scenario" className="input" value={code} onChange={(e) => { setCode(e.target.value); setDelay(''); setDups('') }}>
              {scenarios.map((s) => <option key={s.code} value={s.code}>{s.title}</option>)}
            </select>
          </div>
          {scenario && (
            <dl className="space-y-2 rounded-xl bg-slate-50 p-3 text-xs text-slate-700">
              <div><dt className="font-semibold">What happens</dt><dd>{scenario.whatHappens}</dd></div>
              <div><dt className="font-semibold">Expected outcome</dt><dd>{scenario.expectedOutcome}</dd></div>
              <div><dt className="font-semibold">How PaySure recovers</dt><dd>{scenario.recovery}</dd></div>
            </dl>
          )}
          <div className="grid grid-cols-3 gap-3">
            <label className="text-xs text-slate-600">Amount (₹)
              <input className="input mt-1" inputMode="decimal" value={amount} onChange={(e) => setAmount(e.target.value)} />
            </label>
            <label className="text-xs text-slate-600">Delay (s)
              <input type="number" min={0} max={600} className="input mt-1" placeholder={String(scenario?.defaultDelaySeconds ?? 0)}
                value={delay} onChange={(e) => setDelay(e.target.value === '' ? '' : Number(e.target.value))} />
            </label>
            <label className="text-xs text-slate-600">Callbacks ×
              <input type="number" min={1} max={10} className="input mt-1" placeholder={String(scenario?.defaultDuplicates ?? 1)}
                value={dups} onChange={(e) => setDups(e.target.value === '' ? '' : Number(e.target.value))} />
            </label>
          </div>
          <button type="button" className="btn-primary w-full" disabled={busy || !scenario} onClick={() => void start()}>
            {busy && !view ? 'Starting…' : 'Start simulation'}
          </button>
          {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}
          {runs.length > 0 && (
            <div>
              <div className="label">Recent runs</div>
              <ul className="space-y-1 text-sm">
                {runs.map((r) => (
                  <li key={r.id}>
                    <button type="button" className="flex w-full items-center justify-between rounded-lg px-2 py-1 text-left hover:bg-slate-50" onClick={() => void open(r.id)}>
                      <span>{r.scenarioTitle}</span>
                      {r.paymentStatus && <StatusBadge status={r.paymentStatus} />}
                    </button>
                  </li>
                ))}
              </ul>
            </div>
          )}
        </section>

        <div className="space-y-6 lg:col-span-2">
          {!view && <div className="card text-sm text-slate-600">Pick a scenario and press <strong>Start simulation</strong>. The timeline, balances, ledger and reconciliation result appear here.</div>}
          {view && <RunPanel view={view} busy={busy} onAct={(a, n) => void act(a, n)} />}
        </div>
      </div>

      <ScoreCard score={score} history={history} />
    </div>
  )
}

function Balances({ title, b, status }: { title: string; b: LabBalances; status?: string }) {
  return (
    <div className="rounded-xl border border-slate-200 p-3">
      <div className="mb-2 text-xs font-semibold uppercase tracking-wide text-slate-500">{title}</div>
      <dl className="space-y-1 text-sm">
        <div className="flex justify-between"><dt className="text-slate-500">Payer balance</dt><dd className="tabular-nums">{formatPaise(b.payerBalancePaise)}</dd></div>
        <div className="flex justify-between"><dt className="text-slate-500">Payer held</dt><dd className="tabular-nums">{formatPaise(b.payerReservedPaise)}</dd></div>
        <div className="flex justify-between"><dt className="text-slate-500">Payee balance</dt><dd className="tabular-nums">{formatPaise(b.payeeBalancePaise)}</dd></div>
        {status && <div className="flex justify-between"><dt className="text-slate-500">Payment</dt><dd><StatusBadge status={status} /></dd></div>}
      </dl>
    </div>
  )
}

function RunPanel({ view, busy, onAct }: { view: LabView; busy: boolean; onAct: (a: LabAction, repeat?: number) => void }) {
  const p = view.payment
  const bank = view.simulatedBank
  return (
    <>
      <section className="card space-y-3" aria-label="Run summary">
        <div className="flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-lg font-semibold">{view.scenario.title}</h2>
            <div className="font-mono text-xs text-slate-500">{view.payerVpa} → {view.payeeVpa} · {formatPaise(view.amountPaise)} · ref {p.bankReference}</div>
          </div>
          <div className="flex items-center gap-2">
            <StatusBadge status={p.status} />
            {p.needsManualReview && <StatusBadge status="ESCALATED" />}
          </div>
        </div>
        <div className="flex flex-wrap gap-2" aria-label="Recovery actions">
          {view.availableActions.map((a) => (
            <button key={a} type="button" className={a === 'RECONCILE' ? 'btn-primary' : 'btn-secondary'} disabled={busy} onClick={() => onAct(a)}>
              {ACTION_LABEL[a]}
            </button>
          ))}
          {view.availableActions.includes('RECONCILE') && (
            <button type="button" className="btn-secondary" disabled={busy} onClick={() => onAct('RECONCILE', 3)}>Run reconciliation ×3</button>
          )}
          {view.finished && <Link className="btn-secondary" to={`/payments/${p.id}`}>Open payment</Link>}
        </div>
        {!view.statusApiUp && <p className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-800">The simulated bank's status API is DOWN — PaySure cannot verify outcomes right now.</p>}
      </section>

      <section className="card" aria-label="Before and after">
        <h2 className="mb-3 font-semibold">Before vs. after recovery</h2>
        <div className="grid gap-3 sm:grid-cols-3">
          <Balances title="Before" b={view.before} />
          <Balances title="After the fault" b={view.afterSubmit} status={view.statusAfterSubmit} />
          <Balances title="Now" b={view.now} status={p.status} />
        </div>
        <p className="mt-3 text-sm text-slate-600">
          Reliability score: <strong>{view.scoreBefore ?? '—'}</strong> before → <strong>{view.scoreNow ?? '—'}</strong> now
          <span className="text-xs text-slate-400"> (demo score, rolling window)</span>
        </p>
      </section>

      <div className="grid gap-6 md:grid-cols-2">
        <section className="card" aria-label="Event timeline">
          <h2 className="mb-3 font-semibold">Event timeline</h2>
          <ol className="space-y-3">
            {view.timeline.map((e, i) => (
              <li key={i} className="text-sm">
                <div className="flex flex-wrap items-center gap-2">
                  <span className={`rounded-full px-2 py-0.5 text-[10px] font-bold ${ACTOR_STYLE[e.actor]}`}>{e.actor}</span>
                  <span className="font-medium">{e.title}</span>
                  <span className="text-xs text-slate-400">{formatTime(e.at)}</span>
                </div>
                {e.detail && <p className="ml-1 mt-0.5 text-xs text-slate-600">{e.detail}</p>}
              </li>
            ))}
          </ol>
        </section>

        <div className="space-y-6">
          <section className="card" aria-label="Simulated bank response">
            <h2 className="mb-2 font-semibold">Simulated bank</h2>
            {bank ? (
              <dl className="grid grid-cols-2 gap-x-3 gap-y-1 text-sm">
                <dt className="text-slate-500">Actually committed</dt><dd><StatusBadge status={bank.committedStatus} /></dd>
                <dt className="text-slate-500">Told PaySure</dt><dd className="font-semibold">{bank.responseDelivered}</dd>
                <dt className="text-slate-500">Callbacks</dt><dd>{bank.callbacks.filter((c) => c.deliveredAt).length} of {bank.callbacks.length} delivered</dd>
              </dl>
            ) : <p className="text-sm text-slate-500">The request never reached the bank.</p>}
          </section>

          <section className="card" aria-label="Ledger state">
            <h2 className="mb-2 font-semibold">Ledger</h2>
            {view.ledger.length === 0 ? <p className="text-sm text-slate-500">No ledger posting (no money has moved).</p> : (
              <ul className="space-y-1 text-sm">
                {view.ledger.map((l, i) => (
                  <li key={i} className="flex justify-between"><span>{l.direction} · {l.account.toLowerCase()}</span><span className="tabular-nums">{formatPaise(l.amountPaise)}</span></li>
                ))}
                <li className="text-xs text-slate-500">One balanced journal: debits equal credits.</li>
              </ul>
            )}
          </section>

          <section className="card" aria-label="Reconciliation result">
            <h2 className="mb-2 font-semibold">Reconciliation result</h2>
            {view.reconciliation.length === 0 ? <p className="text-sm text-slate-500">Not reconciled yet.</p> : (
              <ul className="space-y-2 text-sm">
                {view.reconciliation.map((i) => (
                  <li key={i.id}><StatusBadge status={i.outcome} /> <span className="text-slate-600">{i.detail}</span></li>
                ))}
              </ul>
            )}
          </section>
        </div>
      </div>

      <section className="card" aria-label="What happened and why">
        <h2 className="mb-2 font-semibold">What happened, and why</h2>
        <ul className="list-disc space-y-1 pl-5 text-sm text-slate-700">
          {view.explanation.map((t, i) => <li key={i}>{t}</li>)}
        </ul>
      </section>
    </>
  )
}
