import { expect, type APIRequestContext, type Page } from '@playwright/test'

/** Demo password of the seeded users (docker .env.example and the dev profile both use it). */
export const DEMO_PASSWORD = process.env.PAYSURE_DEMO_PASSWORD ?? 'Sandbox@123'
export const PASSWORD = 'Passw0rd!x'

export interface Person {
  email: string
  token: string
  accountId: string
  vpa: string
  name: string
}

function unique(prefix: string): string {
  return `${prefix}${Date.now().toString(36)}${Math.random().toString(36).slice(2, 7)}`
}

/** Registers a fresh user and opens a funded sandbox account with a VPA, through the public API (fast, isolated). */
export async function newPerson(api: APIRequestContext, prefix: string): Promise<Person> {
  const handle = unique(prefix).toLowerCase().slice(0, 24)
  const email = `${handle}@example.com`
  const name = `${prefix[0].toUpperCase()}${prefix.slice(1)} Tester`
  const reg = await api.post('/api/auth/register', { data: { email, password: PASSWORD, fullName: name } })
  expect(reg.status(), 'register').toBe(201)
  const token = await login(api, email, PASSWORD)
  const acc = await api.post('/api/accounts', {
    headers: { Authorization: `Bearer ${token}` },
    data: { displayName: 'Savings', vpaHandle: handle },
  })
  expect(acc.status(), 'open account').toBe(201)
  const body = await acc.json()
  return { email, token, accountId: body.id, vpa: `${handle}@paysure`, name }
}

export async function login(api: APIRequestContext, email: string, password: string): Promise<string> {
  const res = await api.post('/api/auth/login', { data: { email, password } })
  expect(res.status(), `login ${email}`).toBe(200)
  return (await res.json()).accessToken
}

export async function pay(api: APIRequestContext, from: Person, toVpa: string, amountPaise: number, note = 'e2e') {
  const res = await api.post('/api/payments', {
    headers: { Authorization: `Bearer ${from.token}`, 'Idempotency-Key': `e2e-${Date.now()}-${Math.random().toString(36).slice(2)}` },
    data: { payerVpa: from.vpa, payeeVpa: toVpa, amountPaise, note },
  })
  expect(res.status(), 'payment accepted').toBeLessThan(300)
  return res.json()
}

/** Signs in through the real login form. */
export async function signIn(page: Page, email: string, password: string) {
  await page.goto('/')
  await page.getByLabel('E-mail').fill(email)
  await page.getByLabel('Password').fill(password)
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard/)
}

export const rupees = (paise: number) =>
  `₹${new Intl.NumberFormat('en-IN').format(Math.floor(paise / 100))}.${String(paise % 100).padStart(2, '0')}`
