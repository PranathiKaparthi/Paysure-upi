import { expect, test } from '@playwright/test'
import { PASSWORD, newPerson, signIn } from './helpers'

test('send money through the UI: result, timeline, history and both balances', async ({ page, request }) => {
  const asha = await newPerson(request, 'asha')
  const bala = await newPerson(request, 'bala')

  await signIn(page, asha.email, PASSWORD)
  await page.getByRole('link', { name: 'Send Payment' }).click()
  await page.getByLabel('To (payee VPA)').fill(bala.vpa)
  await expect(page.getByText(`✓ ${bala.name}`)).toBeVisible()                // payee verified by name before paying
  await page.getByLabel(/^Amount/).fill('250.50')
  await page.getByLabel('Note').fill('Dinner')
  await page.getByRole('button', { name: /^Pay/ }).click()

  const result = page.getByRole('status', { name: 'Payment result' })
  await expect(result).toContainText('SUCCESS')
  await expect(result).toContainText('₹250.50')
  await result.getByRole('link', { name: /View details/ }).click()

  const timeline = page.getByRole('region', { name: 'Status timeline' })
  for (const step of ['CREATED', 'PROCESSING', 'SUCCESS']) {
    await expect(timeline).toContainText(step)
  }
  await expect(page.getByRole('region', { name: 'Bank processing' })).toContainText('1 (never re-sent)')

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'History' }).click()
  const row = page.getByRole('row', { name: new RegExp(bala.vpa) })
  await expect(row).toContainText('−₹250.50')
  await expect(row).toContainText('SUCCESS')

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Dashboard' }).click()
  await expect(page.getByTestId('balance')).toHaveText('₹9,749.50')

  await page.getByRole('button', { name: 'Sign out' }).click()
  await signIn(page, bala.email, PASSWORD)
  await expect(page.getByTestId('balance')).toHaveText('₹10,250.50')
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'History' }).click()
  await expect(page.getByRole('row', { name: new RegExp(asha.vpa) })).toContainText('+₹250.50')
})

test('request money, approve it as the payer, and both balances move exactly once', async ({ page, request }) => {
  const shop = await newPerson(request, 'shop')
  const buyer = await newPerson(request, 'buyer')

  await signIn(page, shop.email, PASSWORD)
  await page.getByRole('link', { name: 'Request Money' }).click()
  await page.getByLabel('From (payer VPA)').fill(buyer.vpa)
  await page.getByLabel('Amount (₹)').fill('100')
  await page.getByLabel('Note').fill('Tea')
  await page.getByRole('button', { name: 'Send request' }).click()
  await expect(page.getByRole('status')).toContainText('Requested ₹100.00')
  await expect(page.getByRole('region', { name: 'Your requests' })).toContainText('PENDING')

  await page.getByRole('button', { name: 'Sign out' }).click()
  await signIn(page, buyer.email, PASSWORD)
  await page.getByRole('link', { name: 'Request Money' }).click()
  const incoming = page.getByRole('region', { name: 'Requests to you' })
  await expect(incoming).toContainText(shop.vpa)
  await incoming.getByRole('button', { name: 'Pay' }).click()
  await expect(page.getByRole('status')).toContainText('Paid ₹100.00')
  await expect(incoming).toContainText('APPROVED')

  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Dashboard' }).click()
  await expect(page.getByTestId('balance')).toHaveText('₹9,900.00')
})

test('a lost response is retried safely: the same Idempotency-Key is reused and the payer is charged once', async ({ page, request }) => {
  const payer = await newPerson(request, 'retry')
  const payee = await newPerson(request, 'retryp')
  const keys: string[] = []
  let dropped = false

  // The server processes the first request, but the browser never sees the response (a lost reply).
  await page.route('**/api/payments', async (route) => {
    if (route.request().method() !== 'POST') return route.continue()
    keys.push(route.request().headers()['idempotency-key'])
    if (!dropped) {
      dropped = true
      await route.fetch()      // let it reach the server...
      return route.abort('failed')   // ...but lose the answer
    }
    return route.continue()
  })

  await signIn(page, payer.email, PASSWORD)
  await page.getByRole('link', { name: 'Send Payment' }).click()
  await page.getByLabel('To (payee VPA)').fill(payee.vpa)
  await page.getByLabel(/^Amount/).fill('75')
  await page.getByRole('button', { name: /^Pay/ }).click()

  await expect(page.getByRole('alert')).toContainText('may or may not have been created')
  await page.getByRole('button', { name: 'Retry safely' }).click()

  const result = page.getByRole('status', { name: 'Payment result' })
  await expect(result).toContainText('SUCCESS')
  await expect(result).toContainText('Idempotent replay')
  expect(keys).toHaveLength(2)
  expect(keys[1]).toBe(keys[0])

  const list = await request.get('/api/payments', { headers: { Authorization: `Bearer ${payer.token}` } })
  expect(await list.json()).toHaveLength(1)                                  // exactly one logical payment
  const acc = await request.get(`/api/accounts/${payer.accountId}/balance`, { headers: { Authorization: `Bearer ${payer.token}` } })
  expect((await acc.json()).balancePaise).toBe(1_000_000 - 7_500)           // charged once
})

test('an invalid amount and an unknown payee are refused with clear messages', async ({ page, request }) => {
  const user = await newPerson(request, 'bad')
  await signIn(page, user.email, PASSWORD)
  await page.getByRole('link', { name: 'Send Payment' }).click()
  await page.getByLabel('To (payee VPA)').fill(`nobody${Date.now()}@paysure`)
  await page.getByLabel(/^Amount/).fill('12.345')
  await page.getByRole('button', { name: /^Pay/ }).click()
  await expect(page.getByRole('alert').first()).toContainText('at most two decimals')

  await page.getByLabel(/^Amount/).fill('10')
  await page.getByRole('button', { name: /^Pay/ }).click()
  await expect(page.getByRole('alert').first()).toContainText('Payee VPA not found')
})
