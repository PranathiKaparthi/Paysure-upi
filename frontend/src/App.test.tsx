import { render, screen, waitFor } from '@testing-library/react'
import userEvent from '@testing-library/user-event'
import { MemoryRouter } from 'react-router-dom'
import { describe, expect, it, vi } from 'vitest'
import { AppRoutes } from './App'
import type { Account } from './api/types'
import { AuthProvider } from './auth/AuthContext'

const account: Account = {
  id: 'acc-1',
  displayName: 'Ananya Savings',
  maskedAccountNumber: 'XXXXXXXX7947',
  ifsc: 'PSBK0000001',
  currency: 'INR',
  status: 'ACTIVE',
  balancePaise: 1000000,
  reservedPaise: 0,
  availablePaise: 1000000,
  vpas: [{ id: 'v1', vpa: 'ananya@paysure', accountId: 'acc-1', status: 'ACTIVE', createdAt: '2026-11-20T09:00:00Z' }],
  createdAt: '2026-11-20T09:00:00Z',
}

function json(status: number, body: unknown) {
  return Promise.resolve(new Response(JSON.stringify(body), { status, headers: { 'Content-Type': 'application/json' } }))
}

function renderApp(path = '/') {
  return render(
    <AuthProvider>
      <MemoryRouter initialEntries={[path]}>
        <AppRoutes />
      </MemoryRouter>
    </AuthProvider>,
  )
}

describe('PaySure app', () => {
  it('always shows the sandbox disclaimer', () => {
    renderApp()
    expect(screen.getByRole('note')).toHaveTextContent(/SANDBOX — SIMULATED PAYMENTS/)
  })

  it('shows the backend problem detail on failed login', async () => {
    vi.spyOn(globalThis, 'fetch').mockImplementation(() =>
      json(401, { status: 401, code: 'INVALID_CREDENTIALS', detail: 'Invalid e-mail or password.' }),
    )
    renderApp()
    await userEvent.type(screen.getByLabelText(/e-mail/i), 'ananya@example.com')
    await userEvent.type(screen.getByLabelText(/password/i), 'wrong')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))
    expect(await screen.findByRole('alert')).toHaveTextContent('Invalid e-mail or password.')
  })

  it('logs in, sends the bearer token and renders accounts from the API', async () => {
    const fetchMock = vi.spyOn(globalThis, 'fetch').mockImplementation((input) => {
      const url = String(input)
      if (url.endsWith('/api/auth/login')) {
        return json(200, {
          accessToken: 'token-123',
          tokenType: 'Bearer',
          expiresIn: 3600,
          user: { id: 'u1', email: 'ananya@example.com', fullName: 'Ananya Sharma', role: 'USER', createdAt: '' },
        })
      }
      if (url.endsWith('/api/accounts')) return json(200, [account])
      return json(404, { status: 404, code: 'NOT_FOUND', detail: 'nope' })
    })

    renderApp()
    await userEvent.type(screen.getByLabelText(/e-mail/i), 'ananya@example.com')
    await userEvent.type(screen.getByLabelText(/password/i), 'Sandbox@123')
    await userEvent.click(screen.getByRole('button', { name: 'Sign in' }))

    expect(await screen.findByText('Welcome, Ananya')).toBeInTheDocument()
    expect(await screen.findByText('ananya@paysure')).toBeInTheDocument()
    expect(screen.getByTestId('balance')).toHaveTextContent('₹10,000.00')
    // Admin-only sandbox tooling is not rendered for USER.
    expect(screen.queryByRole('form', { name: 'Sandbox top-up' })).not.toBeInTheDocument()

    await waitFor(() => {
      const accountsCall = fetchMock.mock.calls.find(([u]) => String(u).endsWith('/api/accounts'))
      expect(accountsCall).toBeDefined()
      const headers = (accountsCall![1] as RequestInit).headers as Record<string, string>
      expect(headers.Authorization).toBe('Bearer token-123')
    })
  })

  it('redirects unauthenticated users away from the dashboard', () => {
    renderApp('/dashboard')
    expect(screen.getByRole('heading', { name: /Reliability Lab/ })).toBeInTheDocument()
  })
})
