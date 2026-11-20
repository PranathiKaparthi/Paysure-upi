import { render, screen, within } from '@testing-library/react'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { MonitoringPage } from './MonitoringPage'

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

const summary = {
  generatedAt: '2026-11-20T10:00:00Z',
  payments: { windowHours: 24, total: 120, success: 100, failed: 15, unfinished: 5, successRate: 0.8695, p50Ms: 80, p95Ms: 2400, pendingNow: 5, oldestPendingSeconds: 42 },
  countersSinceProcessStart: { paymentRequests: 120, duplicateRequestsPrevented: 7, webhookDeliveryFailures: 3 },
  pipeline: { outboxPending: 2, outboxDead: 0, oldestPendingEventSeconds: 1, webhookDeliveriesOpen: 1, webhookDeliveriesDead: 0 },
  risk: { open: 4, high24h: 1, medium24h: 3, blocked24h: 0 },
  dependencies: [
    { name: 'PostgreSQL', status: 'UP', detail: 'System of record; query answered.' },
    { name: 'Redis', status: 'DEGRADED', detail: 'Recent errors: using in-memory rate limits and database reads.' },
    { name: 'Kafka (inferred from the outbox)', status: 'UP', detail: 'Outbox is draining.' },
  ],
}
const bucket = (n: number) => ({ t: `2026-11-20T10:${String(n).padStart(2, '0')}:00Z`, created: 0, success: 0, failed: 0, unfinished: 0, delivered: 0, resolved: 0, unresolved: 0, discrepancies: 0 })
const series = { payments: [bucket(0), bucket(1)], webhooks: [bucket(0)], reconciliation: [bucket(0)] }
const score = { windowStart: '', computedAt: '', score: 88, methodology: 'weighted', disclaimer: 'A configurable demo score, not an industry-standard benchmark.', components: [] }

describe('MonitoringPage', () => {
  it('shows real figures, honest dependency status and the labelled process counters', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/monitoring/summary')) return json(200, summary)
      if (url.endsWith('/monitoring/series')) return json(200, series)
      if (url.includes('/reliability/history')) return json(200, [])
      if (url.includes('/reliability')) return json(200, score)
      return json(404, { status: 404, code: 'NOT_FOUND', detail: 'x' })
    })
    sessionStorage.setItem('paysure.session', JSON.stringify({
      token: 't', expiresAt: Date.now() + 60_000, user: { id: 'u1', email: 'l@example.com', fullName: 'Lab', role: 'ADMIN_DEMO', createdAt: '' },
    }))
    render(<AuthProvider><MemoryRouter><MonitoringPage /></MemoryRouter></AuthProvider>)

    const payments = await screen.findByRole('region', { name: 'Payments, last 24 hours' })
    expect(within(payments).getByText('120')).toBeInTheDocument()
    expect(within(payments).getByText('87.0 %')).toBeInTheDocument()
    expect(within(payments).getByText('80 ms / 2.4 s')).toBeInTheDocument()
    expect(within(payments).getByText('oldest 42 s')).toBeInTheDocument()

    const deps = screen.getByRole('region', { name: 'Dependencies' })
    expect(within(deps).getByText('DEGRADED')).toBeInTheDocument()
    expect(within(deps).getByText(/Kafka \(inferred from the outbox\)/)).toBeInTheDocument()

    const counters = screen.getByRole('region', { name: 'Counters since process start' })
    expect(within(counters).getByText('duplicate requests prevented')).toBeInTheDocument()
    expect(within(counters).getByText(/reset when the backend restarts/)).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Reliability score' })).toHaveTextContent(/not an industry-standard benchmark/)
  })
})
