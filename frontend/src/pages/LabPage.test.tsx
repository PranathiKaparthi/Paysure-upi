import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { LabScenario, LabView } from '../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { LabPage } from './LabPage'

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

const scenario: LabScenario = {
  code: 'LOST_ACKNOWLEDGEMENT', title: 'Lost acknowledgement', category: 'AMBIGUOUS', bankScenario: 'TIMEOUT_AFTER_COMMIT',
  defaultDelaySeconds: 0, defaultDuplicates: 1, defaultRequests: 1, statusApiDown: false,
  whatHappens: 'The bank commits but its reply is lost.', expectedOutcome: 'PENDING then SUCCESS.', recovery: 'Reconciliation asks the bank.',
}

const bal = (reserved: number, payee: number, payer = 1000000) => ({ payerBalancePaise: payer, payerReservedPaise: reserved, payeeBalancePaise: payee })

const pending: LabView = {
  id: 'run-1', scenario, createdAt: '2026-11-20T10:00:00Z', amountPaise: 50000, delaySeconds: 0, duplicateCallbacks: 1,
  requestsSent: 1, distinctPayments: 1, payerVpa: 'labadmin@paysure', payeeVpa: 'rahul@paysure',
  before: bal(0, 1000000), afterSubmit: bal(50000, 1000000), now: bal(50000, 1000000), statusAfterSubmit: 'PENDING',
  payment: { id: 'pay-1', status: 'PENDING', holdStatus: 'HELD', amountPaise: 50000, bankReference: 'PSB1', needsManualReview: false },
  statusApiUp: true, ledger: [], reconciliation: [], scoreBefore: 100, scoreNow: 72, finished: false,
  timeline: [
    { at: '2026-11-20T10:00:00Z', actor: 'CLIENT', title: 'Payment request sent' },
    { at: '2026-11-20T10:00:01Z', actor: 'BANK', title: 'Bank replied: TIMEOUT', detail: 'Ambiguous reply' },
  ],
  explanation: ['PaySure holds the funds and keeps the payment PENDING. It will never re-send the transfer.'],
  availableActions: ['RECONCILE'],
}

const recovered: LabView = {
  ...pending, now: bal(0, 1050000, 950000), payment: { ...pending.payment, status: 'SUCCESS', holdStatus: 'CAPTURED' }, finished: true, scoreNow: 89,
  ledger: [{ direction: 'DEBIT', account: 'PAYER', amountPaise: 50000 }, { direction: 'CREDIT', account: 'PAYEE', amountPaise: 50000 }],
  reconciliation: [{ id: 'i1', runId: 'r', kind: 'IN_FLIGHT', outcome: 'RESOLVED', detail: 'Bank confirmed SUCCESS', createdAt: '2026-11-20T10:00:05Z' }],
  timeline: [...pending.timeline, { at: '2026-11-20T10:00:05Z', actor: 'RECONCILER', title: 'Reconciliation: RESOLVED' }],
  availableActions: [],
}

const score = {
  windowStart: '', computedAt: '', score: 72, methodology: 'weighted average', disclaimer: 'A configurable demo score, not an industry-standard benchmark.',
  components: [
    { key: 'success_rate', label: 'Confirmed payment success rate', weight: 30, available: true, valueText: '100.0 % (3 of 3)', score: 100, explanation: '' },
    { key: 'webhook_delivery', label: 'Webhook delivery success rate', weight: 10, available: false, valueText: 'Not measurable yet', explanation: '' },
  ],
}

describe('LabPage', () => {
  it('starts a scenario, shows the PENDING state, and recovers via reconciliation', async () => {
    sessionStorage.setItem('paysure.session', JSON.stringify({
      token: 't', expiresAt: Date.now() + 60_000,
      user: { id: 'u1', email: 'labadmin@example.com', fullName: 'Lab Admin', role: 'ADMIN_DEMO', createdAt: '' },
    }))
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url = String(input)
      const method = (init as RequestInit | undefined)?.method ?? 'GET'
      if (url.endsWith('/lab/scenarios')) return json(200, [scenario])
      if (url.includes('/lab/runs') && method === 'GET' && !url.includes('/lab/runs/')) return json(200, [])
      if (url.endsWith('/lab/runs') && method === 'POST') return json(201, pending)
      if (url.endsWith('/actions')) return json(200, recovered)
      if (url.includes('/reliability/history')) return json(200, [])
      if (url.includes('/reliability')) return json(200, score)
      return json(404, { status: 404, code: 'NOT_FOUND', detail: 'x' })
    })

    render(<AuthProvider><MemoryRouter><LabPage /></MemoryRouter></AuthProvider>)
    expect(await screen.findByText(/The bank commits but its reply is lost/)).toBeInTheDocument()

    await userEvent.click(screen.getByRole('button', { name: 'Start simulation' }))
    const summary = await screen.findByRole('region', { name: 'Run summary' })
    expect(within(summary).getByText('PENDING')).toBeInTheDocument()
    expect(screen.getByText('Bank replied: TIMEOUT')).toBeInTheDocument()
    expect(screen.getByText(/never re-send the transfer/)).toBeInTheDocument()
    expect(screen.getByText('No ledger posting (no money has moved).')).toBeInTheDocument()

    // The request carried the chosen scenario and the amount in paise.
    const start = fetchMock.mock.calls.find(([u, i]) => String(u).endsWith('/lab/runs') && (i as RequestInit).method === 'POST')!
    expect(JSON.parse((start[1] as RequestInit).body as string)).toMatchObject({ scenario: 'LOST_ACKNOWLEDGEMENT', amountPaise: 50000 })

    await userEvent.click(within(summary).getByRole('button', { name: 'Run reconciliation' }))
    expect(await screen.findByText('Reconciliation: RESOLVED')).toBeInTheDocument()
    expect(screen.getByText(/One balanced journal/)).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Run reconciliation' })).not.toBeInTheDocument()

    // The score card is honest about what it cannot measure.
    const card = screen.getByRole('region', { name: 'Reliability score' })
    expect(within(card).getByTestId('score-value')).toHaveTextContent('72')
    expect(within(card).getByText('excluded')).toBeInTheDocument()
    expect(within(card).getByText(/not an industry-standard benchmark/)).toBeInTheDocument()
  })
})
