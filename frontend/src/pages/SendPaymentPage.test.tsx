import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AuthProvider } from '../auth/AuthContext'
import { SendPaymentPage } from './SendPaymentPage'

function json(status: number, body: unknown, headers: Record<string, string> = {}) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json', ...headers } }))
}

const account = {
  id: 'acc-1', displayName: 'Savings', maskedAccountNumber: 'XXXXXXXX0001', ifsc: 'PSBK0000001', currency: 'INR',
  status: 'ACTIVE', balancePaise: 1000000, reservedPaise: 0, availablePaise: 1000000, createdAt: '',
  vpas: [{ id: 'v1', vpa: 'ananya@paysure', accountId: 'acc-1', status: 'ACTIVE', createdAt: '' }],
}

const payment = {
  id: 'pay-1', status: 'SUCCESS', statusMessage: 'Payment successful.', direction: 'SENT', payerVpa: 'ananya@paysure',
  payeeVpa: 'rahul@paysure', amountPaise: 25050, holdStatus: 'CAPTURED', createdAt: '', updatedAt: '',
}

function renderPage() {
  sessionStorage.setItem('paysure.session', JSON.stringify({
    token: 't', expiresAt: Date.now() + 60_000,
    user: { id: 'u1', email: 'a@example.com', fullName: 'Ananya', role: 'USER', createdAt: '' },
  }))
  return render(<AuthProvider><MemoryRouter><SendPaymentPage /></MemoryRouter></AuthProvider>)
}

describe('SendPaymentPage', () => {
  it('reuses the same Idempotency-Key when retrying after a network error, and converts rupees to paise', async () => {
    let paymentCalls = 0
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/api/accounts')) return json(200, [account])
      if (url.includes('/resolve')) return json(200, { vpa: 'rahul@paysure', payeeName: 'Rahul Verma', ifsc: 'PSBK0000001' })
      if (url.endsWith('/api/payments')) {
        paymentCalls++
        if (paymentCalls === 1) return Promise.reject(new TypeError('Failed to fetch'))
        return json(200, payment, { 'Idempotent-Replayed': 'true' })
      }
      return json(404, { code: 'NOT_FOUND', detail: 'x', status: 404 })
    })

    renderPage()
    await screen.findByRole('option', { name: /ananya@paysure/ })
    await userEvent.type(screen.getByLabelText(/payee VPA/i), 'rahul@paysure')
    await userEvent.type(screen.getByLabelText(/Amount/), '250.50')
    expect(await screen.findByText(/Rahul Verma/)).toBeInTheDocument()
    await userEvent.click(screen.getByRole('button', { name: /^Pay/ }))

    await userEvent.click(await screen.findByRole('button', { name: 'Retry safely' }))
    expect(await screen.findByText(/Idempotent replay/)).toBeInTheDocument()

    const posts = fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/api/payments'))
    expect(posts).toHaveLength(2)
    const keys = posts.map(([, init]) => ((init as RequestInit).headers as Record<string, string>)['Idempotency-Key'])
    expect(keys[0]).toMatch(/^web-/)
    expect(keys[1]).toBe(keys[0])
    expect(JSON.parse((posts[0][1] as RequestInit).body as string).amountPaise).toBe(25050)
  })

  it('uses a NEW key for a new payment after a definitive answer', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/api/accounts')) return json(200, [account])
      if (url.includes('/resolve')) return json(200, { vpa: 'rahul@paysure', payeeName: 'Rahul Verma', ifsc: 'X' })
      return json(201, payment, { 'Idempotent-Replayed': 'false' })
    })
    renderPage()
    await screen.findByRole('option', { name: /ananya@paysure/ })
    await userEvent.type(screen.getByLabelText(/payee VPA/i), 'rahul@paysure')
    await userEvent.type(screen.getByLabelText(/Amount/), '10')
    await userEvent.click(screen.getByRole('button', { name: /^Pay/ }))
    await screen.findByRole('status', { name: 'Payment result' })
    await userEvent.click(screen.getByRole('button', { name: /^Pay/ }))

    await waitFor(() => expect(fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/api/payments'))).toHaveLength(2))
    const keys = fetchMock.mock.calls.filter(([u]) => String(u).endsWith('/api/payments'))
      .map(([, init]) => ((init as RequestInit).headers as Record<string, string>)['Idempotency-Key'])
    expect(keys[0]).not.toBe(keys[1])
  })
})
