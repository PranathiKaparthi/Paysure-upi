import path from 'node:path'
import { fileURLToPath } from 'node:url'
import { expect, test } from '@playwright/test'
import { DEMO_PASSWORD, PASSWORD, newPerson, pay, signIn } from './helpers'

// Captures README screenshots from the real running app. Skipped unless SCREENSHOTS=1 (it is not a test of behaviour):
//   SCREENSHOTS=1 E2E_CHANNEL=msedge npx playwright test screenshots
const OUT = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..', '..', 'docs', 'screenshots')
test.skip(!process.env.SCREENSHOTS, 'set SCREENSHOTS=1 to regenerate docs/screenshots')

const nav = (page: import('@playwright/test').Page, name: string) =>
  page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name }).click()

test('capture the user journey', async ({ page, request }) => {
  const asha = await newPerson(request, 'asha')
  const bala = await newPerson(request, 'bala')
  await pay(request, asha, bala.vpa, 25_050, 'Dinner')
  await pay(request, bala, asha.vpa, 5_000, 'Cab share')

  await signIn(page, asha.email, PASSWORD)
  await expect(page.getByTestId('balance')).toBeVisible()
  await page.screenshot({ path: path.join(OUT, '01-dashboard.png') })

  await nav(page, 'Send Payment')
  await page.getByLabel('To (payee VPA)').fill(bala.vpa)
  await expect(page.getByText(`✓ ${bala.name}`)).toBeVisible()
  await page.getByLabel(/^Amount/).fill('250.50')
  await page.getByLabel('Note').fill('Dinner')
  await page.getByRole('button', { name: /^Pay/ }).click()
  await expect(page.getByRole('status', { name: 'Payment result' })).toContainText('SUCCESS')
  await page.screenshot({ path: path.join(OUT, '02-send-payment.png') })

  await page.getByRole('link', { name: /View details/ }).click()
  await expect(page.getByRole('region', { name: 'Status timeline' })).toContainText('SUCCESS')
  await page.screenshot({ path: path.join(OUT, '03-payment-detail.png'), fullPage: true })

  await nav(page, 'Risk Alerts')
  await page.getByLabel('To', { exact: true }).fill(bala.vpa)
  await page.getByLabel('Amount (₹)').fill('60000')
  await page.getByRole('button', { name: 'Assess' }).click()
  await expect(page.getByRole('status', { name: 'Assessment' })).toBeVisible()
  await page.screenshot({ path: path.join(OUT, '07-risk.png'), fullPage: true })
})

test('capture the reliability lab, webhooks and monitoring', async ({ page, request }) => {
  await signIn(page, 'labadmin@example.com', DEMO_PASSWORD)
  await nav(page, 'Reliability Lab')
  await page.getByLabel('Failure scenario').selectOption({ label: 'Lost acknowledgement' })
  await page.getByRole('button', { name: 'Start simulation' }).click()
  const summary = page.getByRole('region', { name: 'Run summary' })
  await expect(summary).toContainText('PENDING')
  await page.screenshot({ path: path.join(OUT, '04-lab-pending.png'), fullPage: true })
  await page.getByRole('button', { name: 'Run reconciliation', exact: true }).click()
  await expect(summary).toContainText('SUCCESS')
  await page.screenshot({ path: path.join(OUT, '05-lab-recovered.png'), fullPage: true })

  const merchant = await newPerson(request, 'shop')
  const customer = await newPerson(request, 'buyer')
  await page.getByRole('button', { name: 'Sign out' }).click()
  await signIn(page, merchant.email, PASSWORD)
  await nav(page, 'Webhooks')
  await page.getByLabel('Fail first').fill('2')
  await page.getByRole('button', { name: 'Use sandbox receiver' }).click()
  await page.getByLabel('payment.completed').check()
  await page.getByRole('button', { name: 'Register endpoint' }).click()
  await page.getByRole('status', { name: 'Signing secret' }).getByRole('button', { name: 'I have saved it' }).click()
  await pay(request, customer, merchant.vpa, 12_300, 'hook')
  const row = page.getByRole('region', { name: 'Deliveries' }).getByRole('row').filter({ hasText: 'payment.completed' })
  await expect(row).toContainText('SUCCESS', { timeout: 60_000 })
  await row.getByRole('button', { name: 'attempts' }).click()
  await page.screenshot({ path: path.join(OUT, '06-webhooks.png'), fullPage: true })

  await page.getByRole('button', { name: 'Sign out' }).click()
  await signIn(page, 'labadmin@example.com', DEMO_PASSWORD)
  await nav(page, 'Monitoring')
  await expect(page.getByRole('region', { name: 'Dependencies' })).toContainText('PostgreSQL')
  await page.waitForTimeout(1500)
  await page.screenshot({ path: path.join(OUT, '08-monitoring.png'), fullPage: true })
})
