import { expect, test } from '@playwright/test'
import { PASSWORD, newPerson, pay, signIn } from './helpers'

test('a failing endpoint is retried with backoff and the event is delivered, with the secret shown only once', async ({ page, request }) => {
  const merchant = await newPerson(request, 'hook')
  const customer = await newPerson(request, 'cust')
  await signIn(page, merchant.email, PASSWORD)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Webhooks' }).click()

  await page.getByLabel('Fail first').fill('1')
  await page.getByRole('button', { name: 'Use sandbox receiver' }).click()
  await page.getByLabel('payment.completed').check()
  await page.getByRole('button', { name: 'Register endpoint' }).click()

  const secret = page.getByRole('status', { name: 'Signing secret' })
  await expect(secret.getByTestId('secret')).toContainText(/^whsec_/)
  await secret.getByRole('button', { name: 'I have saved it' }).click()
  await expect(page.getByTestId('secret')).toHaveCount(0)                          // never shown again

  await pay(request, customer, merchant.vpa, 12_300, 'hook test')                  // the merchant is the payee

  const deliveries = page.getByRole('region', { name: 'Deliveries' })
  const row = deliveries.getByRole('row').filter({ hasText: 'payment.completed' })
  await expect(row).toContainText('SUCCESS', { timeout: 45_000 })
  await expect(row).toContainText('2/6')                                           // one failure, then success

  await row.getByRole('button', { name: 'attempts' }).click()
  const attempts = page.getByLabel('Delivery attempts')
  await expect(attempts).toContainText('500')
  await expect(attempts).toContainText('200')
})

test('an internal/loopback endpoint is refused at registration (SSRF guard)', async ({ page, request }) => {
  const user = await newPerson(request, 'ssrf')
  await signIn(page, user.email, PASSWORD)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Webhooks' }).click()
  await page.getByLabel('Endpoint URL (https)').fill('https://127.0.0.1/hook')
  await page.getByRole('button', { name: 'Register endpoint' }).click()
  await expect(page.getByRole('alert').first()).toBeVisible()
  await expect(page.getByRole('region', { name: 'Endpoints' })).not.toContainText('127.0.0.1')
})
