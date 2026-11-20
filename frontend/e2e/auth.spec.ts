import { expect, test } from '@playwright/test'
import { DEMO_PASSWORD, PASSWORD, newPerson, signIn } from './helpers'

test('a new user registers, opens a funded sandbox account with a VPA, and signs out', async ({ page }) => {
  const handle = `e2e${Date.now().toString(36)}`
  await page.goto('/')
  await page.getByRole('tab', { name: 'Create account' }).click()
  await page.getByLabel('Full name').fill('Esha Tester')
  await page.getByLabel('E-mail').fill(`${handle}@example.com`)
  await page.getByLabel('Password').fill(PASSWORD)
  await page.getByRole('button', { name: 'Create sandbox account' }).click()

  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.getByText('Welcome, Esha')).toBeVisible()
  await expect(page.getByText(/no sandbox accounts yet/)).toBeVisible()

  await page.getByLabel('Account name').fill('Everyday')
  await page.getByLabel('VPA handle (optional)').fill(handle)
  await page.getByRole('button', { name: 'Open account' }).click()
  await expect(page.getByTestId('balance')).toHaveText('₹10,000.00')       // sandbox funding came from the ledger
  await expect(page.getByText(`${handle}@paysure`).first()).toBeVisible()

  await page.getByRole('button', { name: 'Ledger' }).click()
  await expect(page.getByRole('cell', { name: 'CREDIT' })).toBeVisible()

  await page.getByRole('button', { name: 'Sign out' }).click()
  await expect(page.getByRole('button', { name: 'Create sandbox account' }).or(page.getByRole('tab', { name: 'Sign in' }))).toBeVisible()
})

test('a wrong password is rejected with a generic message and a duplicate VPA is refused', async ({ page, request }) => {
  const owner = await newPerson(request, 'dupe')
  await page.goto('/')
  await page.getByLabel('E-mail').fill(owner.email)
  await page.getByLabel('Password').fill('definitely-not-it1')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page.getByRole('alert')).toHaveText('Invalid e-mail or password.')

  await page.getByLabel('Password').fill('Passw0rd!x')
  await page.getByRole('button', { name: 'Sign in' }).click()
  await expect(page).toHaveURL(/\/dashboard/)
  await page.getByLabel('Account name').fill('Second')
  await page.getByLabel('VPA handle (optional)').fill(owner.vpa.split('@')[0])
  await page.getByRole('button', { name: 'Open account' }).click()
  await expect(page.getByRole('alert')).toContainText('already registered')
})

test('demo-admin pages are hidden from ordinary users (and refused by the server), and shown to demo admins', async ({ page, request }) => {
  const user = await newPerson(request, 'plain')
  await signIn(page, user.email, PASSWORD)
  const nav = page.getByRole('navigation', { name: 'Main' })
  await expect(nav.getByRole('link', { name: 'Send Payment' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Reliability Lab' })).toHaveCount(0)
  await expect(nav.getByRole('link', { name: 'Monitoring' })).toHaveCount(0)

  await page.goto('/lab')
  await expect(page.getByText(/for demo administrators/)).toBeVisible()
  const refused = await request.get('/api/admin/monitoring/summary', { headers: { Authorization: `Bearer ${user.token}` } })
  expect(refused.status()).toBe(403)

  await page.getByRole('button', { name: 'Sign out' }).click()
  await signIn(page, 'labadmin@example.com', DEMO_PASSWORD)
  await expect(nav.getByRole('link', { name: 'Reliability Lab' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Reconciliation' })).toBeVisible()
  await expect(nav.getByRole('link', { name: 'Monitoring' })).toBeVisible()
})
