import { expect, test } from '@playwright/test'
import { DEMO_PASSWORD, signIn } from './helpers'

test('the landing page loads, the sandbox banner is shown, and a seeded user can sign in', async ({ page }) => {
  await page.goto('/')
  await expect(page.getByRole('note')).toContainText('SANDBOX — SIMULATED PAYMENTS')
  await expect(page.getByRole('heading', { name: /Reliability Lab/ })).toBeVisible()
  await signIn(page, 'ananya@example.com', DEMO_PASSWORD)
  await expect(page.getByText('Welcome, Ananya')).toBeVisible()
  await expect(page.getByText('ananya@paysure').first()).toBeVisible()
})
