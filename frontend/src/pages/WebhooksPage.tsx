import { useCallback, useEffect, useState, type FormEvent } from 'react'
import { api } from '../api/client'
import type {
  OutboxStatus,
  WebhookConfig,
  WebhookDelivery,
  WebhookDeliveryDetail,
  WebhookEndpoint,
  WebhookReceipt,
} from '../api/types'
import { useAuth } from '../auth/AuthContext'
import { StatusBadge } from '../components/StatusBadge'
import { errorText, formatTime } from '../lib/format'

export function WebhooksPage() {
  const { user } = useAuth()
  const isAdmin = user?.role === 'ADMIN_DEMO'
  const [config, setConfig] = useState<WebhookConfig | null>(null)
  const [endpoints, setEndpoints] = useState<WebhookEndpoint[]>([])
  const [deliveries, setDeliveries] = useState<WebhookDelivery[]>([])
  const [outbox, setOutbox] = useState<OutboxStatus | null>(null)
  const [receipts, setReceipts] = useState<WebhookReceipt[]>([])
  const [url, setUrl] = useState('')
  const [failFirst, setFailFirst] = useState(2)
  const [types, setTypes] = useState<string[]>([])
  const [secret, setSecret] = useState<{ endpointUrl: string; value: string } | null>(null)
  const [detail, setDetail] = useState<WebhookDeliveryDetail | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [busy, setBusy] = useState(false)

  const refresh = useCallback(async () => {
    const [eps, ds] = await Promise.all([api.webhookEndpoints(), api.webhookDeliveries()])
    setEndpoints(eps)
    setDeliveries(ds)
    if (isAdmin) {
      const [ob, rc] = await Promise.all([api.adminOutbox(), api.adminWebhookReceipts()])
      setOutbox(ob)
      setReceipts(rc)
    }
  }, [isAdmin])

  useEffect(() => {
    let cancelled = false
    api.webhookConfig().then(
      (c) => {
        if (!cancelled) setConfig(c)
      },
      (err: unknown) => {
        if (!cancelled) setError(errorText(err))
      },
    )
    const load = () =>
      refresh().then(
        () => undefined,
        (err: unknown) => {
          if (!cancelled) setError(errorText(err))
        },
      )
    void load()
    const timer = window.setInterval(() => void load(), 3000)
    return () => {
      cancelled = true
      window.clearInterval(timer)
    }
  }, [refresh])

  async function create(e: FormEvent) {
    e.preventDefault()
    setBusy(true)
    setError(null)
    try {
      const created = await api.createWebhookEndpoint({ url: url.trim(), eventTypes: types.length ? types : undefined })
      setSecret({ endpointUrl: created.endpoint.url, value: created.secret })
      setUrl('')
      await refresh()
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  async function act(action: () => Promise<unknown>) {
    setError(null)
    try {
      await action()
      await refresh()
    } catch (err) {
      setError(errorText(err))
    }
  }

  async function rotate(ep: WebhookEndpoint) {
    setError(null)
    try {
      const r = await api.rotateWebhookSecret(ep.id)
      setSecret({ endpointUrl: r.endpoint.url, value: r.secret })
      await refresh()
    } catch (err) {
      setError(errorText(err))
    }
  }

  async function showDetail(id: string) {
    try {
      setDetail(await api.webhookDelivery(id))
    } catch (err) {
      setError(errorText(err))
    }
  }

  const sandboxUrl = config ? `${config.sandboxReceiverUrl}?fail=${failFirst}` : ''

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-2xl font-bold">Webhooks</h1>
        <p className="max-w-3xl text-sm text-slate-500">
          PaySure POSTs signed events (<code>payment.created</code>, <code>payment.completed</code>, <code>payment.failed</code>, …) to your
          endpoints. Delivery is at-least-once with retries, so receivers must deduplicate by the event id. Webhooks only <em>report</em>
          what happened — a retry never moves money.
        </p>
      </div>
      {error && <p role="alert" className="rounded-lg bg-rose-50 px-3 py-2 text-sm text-rose-700">{error}</p>}

      {secret && (
        <section role="status" aria-label="Signing secret" className="card border-amber-300 bg-amber-50">
          <h2 className="font-semibold text-amber-900">Copy your signing secret now — it is shown only once</h2>
          <p className="text-xs text-amber-800">For {secret.endpointUrl}. It is not stored; you can issue a new one with “Rotate secret”.</p>
          <code className="mt-2 block overflow-x-auto rounded-lg bg-white px-3 py-2 font-mono text-sm" data-testid="secret">{secret.value}</code>
          <button type="button" className="btn-secondary mt-3" onClick={() => setSecret(null)}>I have saved it</button>
        </section>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <form onSubmit={(e) => void create(e)} className="card space-y-3 lg:col-span-1" aria-label="Register endpoint">
          <h2 className="font-semibold">Register an endpoint</h2>
          <div>
            <label className="label" htmlFor="wh-url">Endpoint URL (https)</label>
            <input id="wh-url" className="input font-mono text-xs" value={url} onChange={(e) => setUrl(e.target.value)}
              placeholder="https://example.com/paysure-webhook" />
          </div>
          <fieldset>
            <legend className="label">Events (none selected = all)</legend>
            <div className="flex flex-wrap gap-2">
              {(config?.eventTypes ?? []).map((t) => (
                <label key={t} className="flex items-center gap-1 text-xs">
                  <input type="checkbox" checked={types.includes(t)}
                    onChange={(e) => setTypes((cur) => (e.target.checked ? [...cur, t] : cur.filter((x) => x !== t)))} />
                  {t}
                </label>
              ))}
            </div>
          </fieldset>
          <button type="submit" className="btn-primary w-full" disabled={busy || !url.trim()}>Register endpoint</button>

          {config?.sandboxReceiverEnabled && (
            <div className="rounded-xl border border-dashed border-slate-300 p-3 text-xs text-slate-600">
              <div className="mb-1 font-semibold text-slate-700">Local demo: built-in sandbox receiver</div>
              It verifies the signature, deduplicates by event id, and can fail its first N calls per event to show retries.
              <div className="mt-2 flex items-center gap-2">
                <label htmlFor="wh-fail">Fail first</label>
                <input id="wh-fail" type="number" min={0} max={10} className="input w-16 py-1" value={failFirst}
                  onChange={(e) => setFailFirst(Number(e.target.value))} />
                <span>calls</span>
              </div>
              <button type="button" className="btn-secondary mt-2 w-full" onClick={() => setUrl(sandboxUrl)}>Use sandbox receiver</button>
            </div>
          )}
          {config && <p className="text-xs text-slate-500">Signature: <code>{config.signature}</code>. Replay window {config.replayToleranceSeconds}s. Up to {config.maxAttempts} attempts, then dead-letter.</p>}
        </form>

        <section className="card lg:col-span-2" aria-label="Endpoints">
          <h2 className="mb-3 font-semibold">Your endpoints</h2>
          {endpoints.length === 0 && <p className="text-sm text-slate-500">No endpoints yet.</p>}
          <ul className="divide-y divide-slate-100">
            {endpoints.map((ep) => (
              <li key={ep.id} className="flex flex-wrap items-center justify-between gap-2 py-3">
                <div className="min-w-0">
                  <div className="truncate font-mono text-xs">{ep.url}</div>
                  <div className="text-xs text-slate-500">{ep.eventTypes.join(', ')} · secret v{ep.secretVersion}</div>
                </div>
                <div className="flex items-center gap-2">
                  <StatusBadge status={ep.enabled ? 'ACTIVE' : 'INACTIVE'} />
                  <button type="button" className="btn-secondary px-3 py-1" onClick={() => void act(() => api.setWebhookEnabled(ep.id, !ep.enabled))}>
                    {ep.enabled ? 'Disable' : 'Enable'}
                  </button>
                  <button type="button" className="btn-secondary px-3 py-1" onClick={() => void rotate(ep)}>Rotate secret</button>
                </div>
              </li>
            ))}
          </ul>
        </section>
      </div>

      <section className="card overflow-x-auto" aria-label="Deliveries">
        <h2 className="mb-3 font-semibold">Deliveries</h2>
        <table className="w-full text-left text-sm">
          <thead className="text-xs uppercase text-slate-500">
            <tr><th className="py-2">Created</th><th>Event</th><th>Endpoint</th><th>Status</th><th>Attempts</th><th>Last result</th><th /></tr>
          </thead>
          <tbody className="divide-y divide-slate-100">
            {deliveries.length === 0 && <tr><td colSpan={7} className="py-4 text-slate-500">No deliveries yet. Make a payment to generate events.</td></tr>}
            {deliveries.map((d) => (
              <tr key={d.id}>
                <td className="whitespace-nowrap py-2 text-slate-500">{formatTime(d.createdAt)}</td>
                <td className="font-mono text-xs">{d.eventType}</td>
                <td className="max-w-48 truncate font-mono text-xs" title={d.endpointUrl}>{d.endpointUrl}</td>
                <td><StatusBadge status={d.status} /></td>
                <td className="tabular-nums">{d.attempts}/{d.maxAttempts}</td>
                <td className="text-xs">{d.status === 'SUCCESS' ? `delivered ${formatTime(d.deliveredAt)}` : d.lastError ?? (d.status === 'RETRYING' ? `next ${formatTime(d.nextAttemptAt)}` : '—')}</td>
                <td className="space-x-1 whitespace-nowrap">
                  <button type="button" className="text-xs text-brand-700 hover:underline" onClick={() => void showDetail(d.id)}>attempts</button>
                  {d.status === 'DEAD' && (
                    <button type="button" className="btn-secondary px-2 py-0.5 text-xs" onClick={() => void act(() => api.retryWebhookDelivery(d.id))}>Retry</button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
        {detail && (
          <div className="mt-4 rounded-xl bg-slate-50 p-4 text-sm" aria-label="Delivery attempts">
            <div className="mb-2 flex items-center justify-between">
              <div className="font-semibold">Event {detail.delivery.eventId}</div>
              <button type="button" className="text-xs text-slate-500 hover:underline" onClick={() => setDetail(null)}>close</button>
            </div>
            <table className="w-full text-left text-xs">
              <thead className="text-slate-500"><tr><th>#</th><th>When</th><th>HTTP</th><th>Duration</th><th>Outcome</th><th>Error</th></tr></thead>
              <tbody>
                {detail.attempts.map((a) => (
                  <tr key={a.attemptNo}>
                    <td>{a.attemptNo}</td><td>{formatTime(a.startedAt)}</td><td>{a.statusCode ?? '—'}</td>
                    <td>{a.durationMs} ms</td><td>{a.outcome}</td><td>{a.error ?? ''}</td>
                  </tr>
                ))}
              </tbody>
            </table>
            <pre className="mt-3 max-h-40 overflow-auto rounded bg-white p-2 text-[11px]">{detail.payload}</pre>
          </div>
        )}
      </section>

      {isAdmin && (
        <div className="grid gap-6 lg:grid-cols-2">
          <section className="card" aria-label="Event pipeline">
            <h2 className="mb-1 font-semibold">Event pipeline (operator)</h2>
            <p className="mb-3 text-xs text-slate-500">Transactional outbox → Kafka → idempotent consumer (inbox) → webhook deliveries.</p>
            {outbox && (
              <>
                <dl className="mb-3 grid grid-cols-4 gap-2 text-center text-sm">
                  <div><dt className="text-xs text-slate-500">Pending</dt><dd className="text-xl font-bold">{outbox.pending}</dd></div>
                  <div><dt className="text-xs text-slate-500">Published</dt><dd className="text-xl font-bold">{outbox.published}</dd></div>
                  <div><dt className="text-xs text-slate-500">Dead</dt><dd className="text-xl font-bold">{outbox.dead}</dd></div>
                  <div><dt className="text-xs text-slate-500">Inbox</dt><dd className="text-xl font-bold">{outbox.inbox}</dd></div>
                </dl>
                <ul className="max-h-48 space-y-1 overflow-auto text-xs">
                  {outbox.recent.map((r) => (
                    <li key={r.id} className="flex items-center justify-between gap-2">
                      <span className="font-mono">#{r.seq} {r.eventType}</span>
                      <span className="flex items-center gap-2">{r.attempts > 1 && <span className="text-slate-400">{r.attempts} tries</span>}<StatusBadge status={r.status} /></span>
                    </li>
                  ))}
                </ul>
              </>
            )}
          </section>
          <section className="card overflow-x-auto" aria-label="Sandbox receiver">
            <h2 className="mb-1 font-semibold">Sandbox receiver log (operator)</h2>
            <p className="mb-3 text-xs text-slate-500">“Processed” is set at most once per event, however many calls it took.</p>
            <table className="w-full text-left text-xs">
              <thead className="text-slate-500"><tr><th>Event</th><th>Calls</th><th>Result</th><th>Processed</th></tr></thead>
              <tbody>
                {receipts.length === 0 && <tr><td colSpan={4} className="py-3 text-slate-500">Nothing received yet.</td></tr>}
                {receipts.map((r) => (
                  <tr key={`${r.endpointId}-${r.eventId}`}>
                    <td className="font-mono">{r.eventType}</td><td>{r.calls}</td><td>{r.lastResult}</td><td>{r.processedAt ? 'once ✓' : '—'}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </section>
        </div>
      )}
    </div>
  )
}
