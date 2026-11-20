import { expect, test } from '@playwright/test'
import { PASSWORD, newPerson, pay, signIn } from './helpers'

test('the risk simulator explains its score, and is clear that it is not a fraud verdict', async ({ page, request }) => {
  const user = await newPerson(request, 'risk')
  const stranger = await newPerson(request, 'stranger')
  await signIn(page, user.email, PASSWORD)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Risk Alerts' }).click()
  const disclaimer = page.getByText(/Risk alerts are educational/)
  await expect(disclaimer).toBeVisible()
  await expect(disclaimer).toContainText('ALERT')                                  // default mode: alerts only, never refuses

  await page.getByLabel('To', { exact: true }).fill(stranger.vpa)
  await page.getByLabel('Amount (₹)').fill('60000')
  await page.getByRole('button', { name: 'Assess' }).click()
  const result = page.getByRole('status', { name: 'Assessment' })
  await expect(result).toContainText('40')                                         // NEW_RECIPIENT 15 + LARGE_AMOUNT 25
  await expect(result.getByRole('listitem').first()).toBeVisible()                 // every point is explained
})

test('a burst of quick payments to one person raises an explained alert; payments are never blocked in ALERT mode', async ({ page, request }) => {
  const user = await newPerson(request, 'burst')
  const target = await newPerson(request, 'target')
  for (let i = 0; i < 9; i++) await pay(request, user, target.vpa, 100, `burst ${i}`)   // all 9 succeed

  await signIn(page, user.email, PASSWORD)
  await page.getByRole('navigation', { name: 'Main' }).getByRole('link', { name: 'Risk Alerts' }).click()
  const alert = page.getByRole('article', { name: /^Alert \d+$/ }).first()
  await expect(alert).toBeVisible()
  await expect(alert).toContainText('Suggested:')
  await expect(alert).not.toContainText('REFUSED')
})
