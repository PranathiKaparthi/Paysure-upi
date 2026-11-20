import { useEffect, useState } from 'react'
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from 'recharts'
import { api } from '../api/client'
import type { MonitoringSeries, MonitoringSummary, ReliabilityScore, ScoreSnapshot } from '../api/types'
import { ScoreCard } from '../components/ScoreCard'
import { StatusBadge } from '../components/StatusBadge'
import { errorText } from '../lib/format'

const COLORS = { success: '#059669', failed: '#e11d48', unfinished: '#d97706', delivered: '#059669', resolved: '#2453e0', unresolved: '#d97706', discrepancies: '#e11d48' }

function hhmm(iso: string) {
  return new Date(iso).toLocaleTimeString('en-IN', { hour: '2-digit', minute: '2-digit' })
}

function ms(v?: number) {
  return v === undefined || v === null ? '—' : v < 1000 ? `${v} ms` : `${(v / 1000).toFixed(1)} s`
}

export function MonitoringPage() {
  const [summary, setSummary] = useState<MonitoringSummary | null>(null)
  const [series, setSeries] = useState<MonitoringSeries | null>(null)
  const [score, setScore] = useState<ReliabilityScore | null>(null)
  const [history, setHistory] = useState<ScoreSnapshot[]>([])
  const [error, setError] = useState<string | null>(null)

  useEffect(() => {
    let cancelled = false
    const tick = () =>
      Promise.all([api.monitoringSummary(), api.monitoringSeries(), api.reliability(), api.reliabilityHistory()]).then(
        ([s, ser, sc, h]) => {
          if (cancelled) return
          setSummary(s)
          setSeries(ser)
          setScore(sc)
          setHistory(h)
          setError(null)
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
  }, [])

  const c = summary?.countersSinceProcessStart
  const p = summary?.payments

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">System monitoring</h1>
        <p className="text-sm text-slate-500">
          Computed from stored payment, delivery and reconciliation records and from this process's metrics. Refreshes every 5 s.
          Grafana dashboards for the same Prometheus metrics are provisioned in <code>monitoring/</code>.
        </p>
      </div>
      {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}
      {!summary && !error && <div className="card text-sm text-slate-500">Loading…</div>}

      {summary && p && c && (
        <>
          <section className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4" aria-label="Payments, last 24 hours">
            <Stat label="Payments (24 h)" value={String(p.total)} hint={`${p.success} success · ${p.failed} failed · ${p.unfinished} unfinished`} />
            <Stat label="Success rate" value={p.successRate === undefined || p.successRate === null ? '—' : `${(p.successRate * 100).toFixed(1)} %`} hint="of finished payments" />
            <Stat label="Completion latency" value={`${ms(p.p50Ms)} / ${ms(p.p95Ms)}`} hint="median / P95 of successful payments" />
            <Stat label="Pending now" value={String(p.pendingNow)} hint={p.pendingNow ? `oldest ${p.oldestPendingSeconds} s` : 'nothing unresolved'} warn={p.pendingNow > 0} />
          </section>

          <section className="card" aria-label="Dependencies">
            <h2 className="mb-3 font-semibold">Dependencies</h2>
            <ul className="divide-y divide-slate-100">
              {summary.dependencies.map((d) => (
                <li key={d.name} className="flex flex-wrap items-center justify-between gap-2 py-2 text-sm">
                  <div><span className="font-medium">{d.name}</span><span className="ml-2 text-xs text-slate-500">{d.detail}</span></div>
                  <StatusBadge status={d.status} />
                </li>
              ))}
            </ul>
          </section>

          <div className="grid gap-6 lg:grid-cols-2">
            <ChartCard title="Payments per minute (by current status)" label="Payments chart">
              <BarChart data={(series?.payments ?? []).map((b) => ({ ...b, time: hhmm(b.t) }))}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval={4} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} />
                <Tooltip /><Legend />
                <Bar dataKey="success" stackId="p" fill={COLORS.success} isAnimationActive={false} />
                <Bar dataKey="unfinished" stackId="p" fill={COLORS.unfinished} isAnimationActive={false} />
                <Bar dataKey="failed" stackId="p" fill={COLORS.failed} isAnimationActive={false} />
              </BarChart>
            </ChartCard>
            <ChartCard title="Reconciliation results per minute" label="Reconciliation chart">
              <BarChart data={(series?.reconciliation ?? []).map((b) => ({ ...b, time: hhmm(b.t) }))}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval={4} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} />
                <Tooltip /><Legend />
                <Bar dataKey="resolved" stackId="r" fill={COLORS.resolved} isAnimationActive={false} />
                <Bar dataKey="unresolved" stackId="r" fill={COLORS.unresolved} isAnimationActive={false} />
                <Bar dataKey="discrepancies" stackId="r" fill={COLORS.discrepancies} isAnimationActive={false} />
              </BarChart>
            </ChartCard>
            <ChartCard title="Webhook delivery attempts per minute" label="Webhook chart">
              <BarChart data={(series?.webhooks ?? []).map((b) => ({ ...b, time: hhmm(b.t) }))}>
                <CartesianGrid strokeDasharray="3 3" stroke="#e2e8f0" />
                <XAxis dataKey="time" tick={{ fontSize: 10 }} interval={4} />
                <YAxis allowDecimals={false} tick={{ fontSize: 10 }} />
                <Tooltip /><Legend />
                <Bar dataKey="delivered" stackId="w" fill={COLORS.delivered} isAnimationActive={false} />
                <Bar dataKey="failed" stackId="w" fill={COLORS.failed} isAnimationActive={false} />
              </BarChart>
            </ChartCard>

            <section className="card space-y-3" aria-label="Event pipeline and risk">
              <h2 className="font-semibold">Event pipeline and risk</h2>
              <dl className="grid grid-cols-2 gap-x-4 gap-y-1 text-sm">
                <dt className="text-slate-500">Outbox waiting</dt><dd className="tabular-nums">{summary.pipeline.outboxPending}</dd>
                <dt className="text-slate-500">Oldest waiting event</dt><dd className="tabular-nums">{summary.pipeline.oldestPendingEventSeconds} s</dd>
                <dt className="text-slate-500">Outbox dead</dt><dd className="tabular-nums">{summary.pipeline.outboxDead}</dd>
                <dt className="text-slate-500">Webhooks in flight</dt><dd className="tabular-nums">{summary.pipeline.webhookDeliveriesOpen}</dd>
                <dt className="text-slate-500">Webhooks dead-lettered</dt><dd className="tabular-nums">{summary.pipeline.webhookDeliveriesDead}</dd>
                <dt className="text-slate-500">Open risk alerts</dt><dd className="tabular-nums">{summary.risk.open}</dd>
                <dt className="text-slate-500">High / medium (24 h)</dt><dd className="tabular-nums">{summary.risk.high24h} / {summary.risk.medium24h}</dd>
                <dt className="text-slate-500">Refused (24 h)</dt><dd className="tabular-nums">{summary.risk.blocked24h}</dd>
              </dl>
            </section>
          </div>

          <section className="card" aria-label="Counters since process start">
            <h2 className="mb-1 font-semibold">Counters since this process started</h2>
            <p className="mb-3 text-xs text-slate-500">These reset when the backend restarts; the figures above come from the database and do not.</p>
            <dl className="grid gap-x-6 gap-y-2 text-sm sm:grid-cols-2 lg:grid-cols-3">
              {Object.entries(c).map(([k, v]) => (
                <div key={k} className="flex justify-between border-b border-slate-100 py-1">
                  <dt className="text-slate-600">{k.replace(/([A-Z])/g, ' $1').toLowerCase()}</dt>
                  <dd className="tabular-nums">{v}</dd>
                </div>
              ))}
            </dl>
          </section>
        </>
      )}

      <ScoreCard score={score} history={history} />
    </div>
  )
}

function Stat({ label, value, hint, warn }: { label: string; value: string; hint?: string; warn?: boolean }) {
  return (
    <div className="card">
      <div className="label">{label}</div>
      <div className={`text-2xl font-bold tabular-nums ${warn ? 'text-amber-600' : ''}`}>{value}</div>
      {hint && <div className="text-xs text-slate-500">{hint}</div>}
    </div>
  )
}

function ChartCard({ title, label, children }: { title: string; label: string; children: React.ReactElement }) {
  return (
    <section className="card" aria-label={label}>
      <h2 className="mb-2 font-semibold">{title}</h2>
      <div className="h-56">
        <ResponsiveContainer width="100%" height="100%">{children}</ResponsiveContainer>
      </div>
    </section>
  )
}
