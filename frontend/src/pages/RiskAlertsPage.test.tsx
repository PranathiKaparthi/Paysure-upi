import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { RiskAlert } from '../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { RiskAlertsPage } from './RiskAlertsPage'

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

const config = {
  enabled: true, mode: 'ALERT', alertScore: 40, highScore: 70, blockScore: 90,
  rules: [{ code: 'HIGH_VELOCITY', points: 35, threshold: 8, windowSeconds: 60 }, { code: 'LARGE_AMOUNT', points: 25, thresholdPaise: 5000000 }],
  disclaimer: 'Risk alerts are educational simulations produced by transparent rules. They are not real fraud determinations.',
}
const account = {
  id: 'acc-1', displayName: 'Savings', maskedAccountNumber: 'XXXX0001', ifsc: 'PSBK0000001', currency: 'INR', status: 'ACTIVE',
  balancePaise: 1000000, reservedPaise: 0, availablePaise: 1000000, createdAt: '',
  vpas: [{ id: 'v1', vpa: 'ananya@paysure', accountId: 'acc-1', status: 'ACTIVE', createdAt: '' }],
}
const alert: RiskAlert = {
  id: 'al-1', userId: 'u1', paymentId: 'pay-1', payerVpa: 'ananya@paysure', payeeVpa: 'rahul@paysure', amountPaise: 500000, score: 55,
  level: 'MEDIUM', action: 'REVIEW', blocked: false, status: 'OPEN', createdAt: '2026-11-20T10:00:00Z',
  rules: [{ code: 'HIGH_VELOCITY', label: 'High transaction velocity', points: 35, detail: '8 transfers initiated in 60 seconds (threshold 8).' }],
  explanation: ['Risk score: 55/100 (medium).', '8 transfers initiated in 60 seconds (threshold 8). (+35)', 'Several transfers to the same recipient. (+20)'],
  suggestedAction: 'Allow but flag for review: unusual activity that is worth a human glance.',
}

function renderPage(role: 'USER' | 'ADMIN_DEMO') {
  sessionStorage.setItem('paysure.session', JSON.stringify({
    token: 't', expiresAt: Date.now() + 60_000, user: { id: 'u1', email: 'a@example.com', fullName: 'Ananya', role, createdAt: '' },
  }))
  return render(<AuthProvider><MemoryRouter><RiskAlertsPage /></MemoryRouter></AuthProvider>)
}

describe('RiskAlertsPage', () => {
  it('always shows the educational disclaimer, lists explained alerts and assesses a what-if payment', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url = String(input)
      const method = (init as RequestInit | undefined)?.method ?? 'GET'
      if (url.endsWith('/risk/config')) return json(200, config)
      if (url.endsWith('/api/accounts')) return json(200, [account])
      if (url.includes('/risk/alerts')) return json(200, [alert])
      if (url.endsWith('/risk/assess') && method === 'POST') {
        return json(200, {
          score: 40, level: 'MEDIUM', action: 'REVIEW', rules: [], explanation: ['Risk score: 40/100 (medium).', 'Amount ₹5,000.00 is at or above the demo threshold of ₹5,000.00. (+25)'],
          suggestedAction: 'Allow but flag for review.', wouldAlert: true, wouldBlock: false, mode: 'ALERT', disclaimer: config.disclaimer,
        })
      }
      return json(404, { status: 404, code: 'NOT_FOUND', detail: 'x' })
    })
    renderPage('USER')

    expect(await screen.findByRole('note')).toHaveTextContent(/not real fraud determinations/)
    expect(screen.getByRole('note')).toHaveTextContent(/alerts only/)
    const list = screen.getByRole('region', { name: 'Alerts' })
    expect(await within(list).findByText('55')).toBeInTheDocument()
    expect(within(list).getByText(/8 transfers initiated in 60 seconds/)).toBeInTheDocument()
    expect(within(list).getByText(/Suggested:/)).toBeInTheDocument()
    expect(within(list).queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument() // admin-only

    await userEvent.type(await screen.findByLabelText('To'), 'rahul@paysure')
    await userEvent.type(screen.getByLabelText(/Amount/), '5000')
    await userEvent.click(screen.getByRole('button', { name: 'Assess' }))
    const result = await screen.findByRole('status', { name: 'Assessment' })
    expect(within(result).getByText(/demo threshold/)).toBeInTheDocument()
    const call = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/risk/assess'))!
    expect(JSON.parse((call[1] as RequestInit).body as string)).toEqual({ payerVpa: 'ananya@paysure', payeeVpa: 'rahul@paysure', amountPaise: 500000 })
  })

  it('lets a demo admin acknowledge an open alert', async () => {
    let status: RiskAlert['status'] = 'OPEN'
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url = String(input)
      const method = (init as RequestInit | undefined)?.method ?? 'GET'
      if (url.endsWith('/risk/config')) return json(200, config)
      if (url.endsWith('/api/accounts')) return json(200, [account])
      if (url.endsWith('/review') && method === 'POST') {
        status = 'ACKNOWLEDGED'
        return json(200, { ...alert, status })
      }
      if (url.includes('/risk/alerts')) return json(200, [{ ...alert, status }])
      return json(404, { status: 404, code: 'NOT_FOUND', detail: 'x' })
    })
    renderPage('ADMIN_DEMO')
    await userEvent.click(await screen.findByRole('button', { name: 'Acknowledge' }))
    expect(await screen.findByText('ACKNOWLEDGED')).toBeInTheDocument()
    expect(screen.queryByRole('button', { name: 'Acknowledge' })).not.toBeInTheDocument()
    const review = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/review'))!
    expect(JSON.parse((review[1] as RequestInit).body as string)).toEqual({ status: 'ACKNOWLEDGED' })
  })
})
