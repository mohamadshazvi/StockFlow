import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, mode: 'success' | 'conflict' | 'uncertain' = 'success') {
  const user = { id: 'staff-1', email: 'orders@example.test', role: 'STAFF', active: true }
  const product = { id: 'product-1', sku: 'ORDER-001', name: 'Order product', unitPrice: '0.10', stockQuantity: 0 }
  const orders: Record<string, unknown>[] = []
  const writes: Record<string, unknown>[] = []
  let reads = ''
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (path.endsWith('/auth/login')) return route.fulfill({ json: { accessToken: 'mock-token', expiresIn: 900, user } })
    if (path.endsWith('/auth/me')) return route.fulfill({ json: user })
    if (path === '/api/v1/products') return route.fulfill({ json: { content: [product], page: 0, size: 10, totalElements: 1, totalPages: 1 } })
    if (path === '/api/v1/orders' && request.method() === 'POST') {
      const input = request.postDataJSON(); writes.push(input)
      if (mode === 'conflict') return route.fulfill({ status: 409, json: { code: 'PRODUCT_ARCHIVED', message: 'An order product is archived. Remove it before creating the draft.', fieldErrors: [] } })
      // Deliberately return a newer server price, not the picker preview price.
      const order = { id: 'order-1', orderNumber: 'SF-00000001', customerName: input.customerName, reference: input.reference, status: 'DRAFT', currency: 'LKR', total: (input.items[0].quantity * 0.2).toFixed(2), createdBy: user.id, createdAt: '2026-10-21T00:00:00Z', confirmedAt: null, cancelledAt: null, items: [{ id: 'item-1', productId: product.id, skuSnapshot: product.sku, nameSnapshot: product.name, quantity: input.items[0].quantity, unitPriceSnapshot: '0.20', lineTotal: (input.items[0].quantity * 0.2).toFixed(2) }] }
      orders.push(order)
      if (mode === 'uncertain') return route.abort('failed')
      return route.fulfill({ status: 201, json: order })
    }
    if (path === '/api/v1/orders') { reads = url.search; return route.fulfill({ json: { content: url.searchParams.get('status') === 'CONFIRMED' ? [] : orders, page: 0, size: 20, totalElements: orders.length, totalPages: orders.length ? 1 : 0 } }) }
    if (path === '/api/v1/orders/order-1') return route.fulfill({ json: orders[0] })
    return route.fulfill({ status: 404, json: { message: 'Not found', fieldErrors: [] } })
  })
  await page.goto('/login')
  await page.getByLabel('Email address').fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await page.getByRole('link', { name: 'Create draft order', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Create draft order' })).toBeVisible()
  return { writes, reads: () => reads }
}

test('staff creates a draft with exact preview and sees authoritative saved prices', async ({ page }) => {
  const api = await setup(page)
  await page.getByRole('button', { name: 'Add ORDER-001', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Add ORDER-001', exact: true })).toBeDisabled()
  await page.getByLabel('Quantity for ORDER-001', { exact: true }).fill('3')
  await expect(page.getByRole('status').filter({ hasText: 'Estimated total:' })).toContainText('0.30')
  await page.getByLabel('Customer name (optional)', { exact: true }).fill('Customer')
  await page.getByLabel('Reference (optional)', { exact: true }).fill('REF-1')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'SF-00000001' })).toBeVisible()
  await expect(page.getByText('Total: LKR 0.60', { exact: true })).toBeVisible()
  await expect(page.getByRole('heading', { name: 'Order timeline' })).toBeVisible()
  expect(api.writes).toEqual([{ customerName: 'Customer', reference: 'REF-1', items: [{ productId: 'product-1', quantity: 3 }] }])
  await page.getByRole('link', { name: '← Orders', exact: true }).click()
  await expect(page.getByRole('link', { name: 'SF-00000001' })).toBeVisible()
  await page.getByLabel('Status', { exact: true }).selectOption('CONFIRMED')
  await expect(page.getByText('No orders match these filters.')).toBeVisible()
  expect(api.reads()).toContain('status=CONFIRMED')
})

test('empty draft is blocked and failed creation preserves entered details', async ({ page }) => {
  const api = await setup(page, 'conflict')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Add 1–100 products')
  expect(api.writes).toHaveLength(0)
  await page.getByRole('button', { name: 'Add ORDER-001', exact: true }).click()
  await page.getByLabel('Quantity for ORDER-001', { exact: true }).fill('4')
  await page.getByLabel('Reference (optional)', { exact: true }).fill('KEEP-ME')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('archived')
  await expect(page.getByLabel('Reference (optional)', { exact: true })).toHaveValue('KEEP-ME')
  await expect(page.getByLabel('Quantity for ORDER-001', { exact: true })).toHaveValue('4')
  expect(api.writes).toHaveLength(1)
  await page.getByRole('button', { name: 'Remove ORDER-001', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Add ORDER-001', exact: true })).toBeEnabled()
})

test('uncertain committed draft is not retried and can be found in recent orders', async ({ page }) => {
  const api = await setup(page, 'uncertain')
  await page.getByRole('button', { name: 'Add ORDER-001', exact: true }).click()
  await page.getByLabel('Reference (optional)', { exact: true }).fill('CHECK-REF')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('alert')).toContainText('Do not submit again yet')
  await expect(page.getByRole('button', { name: 'Create draft', exact: true })).toBeDisabled()
  expect(api.writes).toHaveLength(1)
  await page.getByRole('link', { name: 'Inspect recent orders' }).click()
  await expect(page.getByText('CHECK-REF', { exact: true })).toBeVisible()
  expect(api.writes).toHaveLength(1)
})
