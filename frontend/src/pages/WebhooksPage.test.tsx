import { render, screen, within } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import type { WebhookDelivery, WebhookEndpoint } from '../api/types'
import { AuthProvider } from '../auth/AuthContext'
import { WebhooksPage } from './WebhooksPage'

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

const config = {
  eventTypes: ['payment.created', 'payment.completed'], sandboxReceiverEnabled: true,
  sandboxReceiverUrl: 'http://localhost:8080/webhook-sandbox/receiver', signature: 'v1=hex(HMAC)', replayToleranceSeconds: 300, maxAttempts: 6,
}
const endpoint: WebhookEndpoint = {
  id: 'ep-1', url: 'http://localhost:8080/webhook-sandbox/receiver?fail=2', eventTypes: ['*'], enabled: true, secretVersion: 1, createdAt: '2026-11-20T10:00:00Z',
}
const dead: WebhookDelivery = {
  id: 'd-1', endpointId: 'ep-1', endpointUrl: endpoint.url, eventId: 'ev-1', eventType: 'payment.completed', status: 'DEAD', attempts: 6, maxAttempts: 6,
  nextAttemptAt: '2026-11-20T10:05:00Z', lastStatusCode: 500, lastError: 'HTTP 500', createdAt: '2026-11-20T10:00:00Z',
}

function renderPage(role: 'USER' | 'ADMIN_DEMO' = 'USER') {
  sessionStorage.setItem('paysure.session', JSON.stringify({
    token: 't', expiresAt: Date.now() + 60_000,
    user: { id: 'u1', email: 'a@example.com', fullName: 'Ananya', role, createdAt: '' },
  }))
  return render(<AuthProvider><MemoryRouter><WebhooksPage /></MemoryRouter></AuthProvider>)
}

describe('WebhooksPage', () => {
  it('registers the sandbox receiver, shows the secret exactly once, and retries a dead-lettered delivery', async () => {
    let deliveries: WebhookDelivery[] = [dead]
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input, init) => {
      const url = String(input)
      const method = (init as RequestInit | undefined)?.method ?? 'GET'
      if (url.endsWith('/webhooks/config')) return json(200, config)
      if (url.endsWith('/webhooks/endpoints') && method === 'GET') return json(200, [])
      if (url.endsWith('/webhooks/endpoints') && method === 'POST') return json(201, { endpoint, secret: 'whsec_ABC123' })
      if (url.includes('/webhooks/deliveries/d-1/retry')) {
        deliveries = [{ ...dead, status: 'PENDING', attempts: 0, lastError: undefined }]
        return json(200, deliveries[0])
      }
      if (url.includes('/webhooks/deliveries')) return json(200, deliveries)
      return json(404, { status: 404, code: 'NOT_FOUND', detail: 'x' })
    })

    renderPage()
    await userEvent.click(await screen.findByRole('button', { name: 'Use sandbox receiver' }))
    expect(screen.getByLabelText(/Endpoint URL/)).toHaveValue('http://localhost:8080/webhook-sandbox/receiver?fail=2')
    await userEvent.click(screen.getByRole('button', { name: 'Register endpoint' }))

    const banner = await screen.findByRole('status', { name: 'Signing secret' })
    expect(within(banner).getByTestId('secret')).toHaveTextContent('whsec_ABC123')
    const post = fetchMock.mock.calls.find(([u, i]) => String(u).endsWith('/webhooks/endpoints') && (i as RequestInit).method === 'POST')!
    expect(JSON.parse((post[1] as RequestInit).body as string).url).toContain('?fail=2')
    await userEvent.click(within(banner).getByRole('button', { name: 'I have saved it' }))
    expect(screen.queryByTestId('secret')).not.toBeInTheDocument()

    // The dead-lettered delivery shows its attempts and offers a manual retry.
    const table = screen.getByRole('region', { name: 'Deliveries' })
    expect(await within(table).findByText('DEAD')).toBeInTheDocument()
    expect(within(table).getByText('6/6')).toBeInTheDocument()
    await userEvent.click(within(table).getByRole('button', { name: 'Retry' }))
    expect(await within(table).findByText('PENDING')).toBeInTheDocument()
    expect(within(table).queryByRole('button', { name: 'Retry' })).not.toBeInTheDocument()
  })

  it('shows operator panels only to demo admins', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/webhooks/config')) return json(200, config)
      if (url.endsWith('/webhooks/endpoints')) return json(200, [])
      if (url.includes('/webhooks/deliveries')) return json(200, [])
      if (url.endsWith('/admin/events/outbox')) return json(200, { pending: 2, published: 40, dead: 0, inbox: 38, recent: [] })
      if (url.endsWith('/admin/webhooks/receipts')) return json(200, [])
      return json(404, { status: 404, code: 'NOT_FOUND', detail: 'x' })
    })
    renderPage('ADMIN_DEMO')
    const pipeline = await screen.findByRole('region', { name: 'Event pipeline' })
    expect(await within(pipeline).findByText('40')).toBeInTheDocument()
    expect(screen.getByRole('region', { name: 'Sandbox receiver' })).toBeInTheDocument()
  })
})
