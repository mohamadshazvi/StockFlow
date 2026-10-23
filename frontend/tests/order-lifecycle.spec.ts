import { test, expect, type Page } from '@playwright/test'

type Options = { status?: string; conflict?: 'stock' | 'archived' | 'state'; lostResponse?: boolean; readFailure?: boolean; hold?: Promise<void> }
async function setup(page: Page, options: Options = {}) {
  const user = { id: 'lifecycle-staff', email: 'lifecycle@example.test', role: 'STAFF', active: true }
  const order = { id: 'lifecycle-order', orderNumber: 'SF-00000006', customerName: 'Lifecycle customer', reference: 'LC-REF', status: options.status ?? 'DRAFT', currency: 'LKR', total: '25.00', createdBy: user.id, createdAt: '2026-10-21T00:00:00Z', confirmedBy: options.status === 'CONFIRMED' ? user.id : null as string | null, confirmedAt: options.status === 'CONFIRMED' ? '2026-10-22T00:00:00Z' : null as string | null, cancelledBy: null as string | null, cancelledAt: null as string | null, items: [{ id: 'item', productId: 'lifecycle-product', skuSnapshot: 'LC-001', nameSnapshot: 'Lifecycle product', quantity: 2, unitPriceSnapshot: '12.50', lineTotal: '25.00' }] }
  const product = { id: 'lifecycle-product', sku: 'LC-001', name: 'Lifecycle product', unitPrice: '12.50', stockQuantity: order.status === 'CONFIRMED' ? 3 : 5, lowStockThreshold: 1, active: true, supplierId: null, description: null, createdAt: order.createdAt }
  const movements: Record<string, unknown>[] = []
  const posts: string[] = []
  let failReads = false
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), path = new URL(request.url()).pathname
    if (path.endsWith('/auth/login')) return route.fulfill({ json: { accessToken: 'mock-token', expiresIn: 900, user } })
    if (path.endsWith('/auth/me')) return route.fulfill({ json: user })
    if (/\/orders\/lifecycle-order\/(confirm|cancel)$/.test(path)) {
      const action = path.endsWith('/confirm') ? 'confirm' : 'cancel'; posts.push(action)
      expect(request.method()).toBe('POST'); expect(request.postData()).toBeNull()
      if (options.hold) await options.hold
      if (options.conflict) {
        if (options.conflict === 'state') { order.status = 'CANCELLED'; order.cancelledBy = user.id; order.cancelledAt = '2026-10-23T00:00:00Z' }
        const code = options.conflict === 'stock' ? 'INSUFFICIENT_STOCK' : options.conflict === 'archived' ? 'PRODUCT_ARCHIVED' : 'INVALID_ORDER_STATE'
        return route.fulfill({ status: 409, json: { code, message: options.conflict === 'stock' ? 'Not enough stock for LC-001.' : options.conflict === 'archived' ? 'An order product is archived.' : 'A cancelled order cannot be confirmed.', fieldErrors: [] } })
      }
      const previous = order.status
      if (action === 'confirm' && previous === 'DRAFT') { order.status = 'CONFIRMED'; order.confirmedBy = user.id; order.confirmedAt = '2026-10-23T00:00:00Z'; product.stockQuantity -= 2 }
      if (action === 'cancel' && previous !== 'CANCELLED') { order.status = 'CANCELLED'; order.cancelledBy = user.id; order.cancelledAt = '2026-10-23T01:00:00Z'; if (previous === 'CONFIRMED') product.stockQuantity += 2 }
      if ((action === 'confirm' && previous === 'DRAFT') || (action === 'cancel' && previous === 'CONFIRMED')) movements.push({ id: `movement-${posts.length}`, productId: product.id, type: action === 'confirm' ? 'ORDER_CONFIRMATION' : 'ORDER_CANCELLATION', quantityDelta: action === 'confirm' ? -2 : 2, orderId: order.id, reference: order.orderNumber, reason: null, createdBy: user.id, createdAt: '2026-10-23T00:00:00Z' })
      if (options.lostResponse) { failReads = Boolean(options.readFailure); return route.abort('failed') }
      return route.fulfill({ json: order })
    }
    if (path === '/api/v1/orders/lifecycle-order') return failReads ? route.abort('failed') : route.fulfill({ json: order })
    if (path === '/api/v1/orders') return route.fulfill({ json: { content: [order], page: 0, size: 20, totalElements: 1, totalPages: 1 } })
    if (path === '/api/v1/products/lifecycle-product/stock-movements') return route.fulfill({ json: { content: movements, page: 0, size: 20, totalElements: movements.length, totalPages: movements.length ? 1 : 0 } })
    if (path === '/api/v1/products/lifecycle-product') return route.fulfill({ json: product })
    return route.fulfill({ status: 404, json: { message: 'Not found.', fieldErrors: [] } })
  })
  await page.goto('/login')
  await page.getByLabel('Email address').fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await page.getByRole('link', { name: order.orderNumber }).click()
  await expect(page.getByRole('heading', { name: `${order.status} order` })).toBeVisible()
  return { posts, allowReads: () => { failReads = false } }
}

test('staff confirms and cancels with audit timeline, refreshed product and signed history', async ({ page }) => {
  const api = await setup(page)
  await page.getByRole('button', { name: 'Confirm order', exact: true }).click()
  let dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('deducts the saved quantities')
  await dialog.getByRole('button', { name: 'Confirm and deduct stock' }).click()
  await expect(dialog).toHaveCount(0)
  await expect(page.getByRole('heading', { name: 'CONFIRMED order' })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Confirm order', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Lifecycle product', exact: true }).click()
  await expect(page.locator('dl').getByText('3', { exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '-2', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Orders', exact: true }).click()
  await page.getByRole('link', { name: 'SF-00000006' }).click()
  await page.getByRole('button', { name: 'Cancel order', exact: true }).click()
  dialog = page.getByRole('dialog')
  await expect(dialog).toContainText('restores all confirmed quantities')
  await dialog.getByRole('button', { name: 'Cancel order permanently' }).click()
  await expect(page.getByRole('heading', { name: 'CANCELLED order' })).toBeVisible()
  await expect(page.getByText('Confirmed:', { exact: false })).toBeVisible()
  await expect(page.getByText('Cancelled:', { exact: false })).toBeVisible()
  await expect(page.getByRole('button', { name: 'Cancel order', exact: true })).toHaveCount(0)
  await page.getByRole('link', { name: 'Lifecycle product', exact: true }).click()
  await expect(page.locator('dl').getByText('5', { exact: true })).toBeVisible()
  await expect(page.getByRole('cell', { name: '+2', exact: true })).toBeVisible()
  expect(api.posts).toEqual(['confirm', 'cancel'])
})

test('draft cancellation explains no stock effect and dialog dismissal sends nothing', async ({ page }) => {
  const api = await setup(page)
  await page.getByRole('button', { name: 'Confirm order', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Keep current state' }).click()
  expect(api.posts).toHaveLength(0)
  await page.getByRole('button', { name: 'Cancel order', exact: true }).click()
  await expect(page.getByRole('dialog')).toContainText('does not change stock')
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel order permanently' }).click()
  await expect(page.getByRole('heading', { name: 'CANCELLED order' })).toBeVisible()
  await page.getByRole('link', { name: 'Lifecycle product', exact: true }).click()
  await expect(page.locator('dl').getByText('5', { exact: true })).toBeVisible()
  await expect(page.getByText('No stock movements on this page.', { exact: false })).toBeVisible()
  expect(api.posts).toEqual(['cancel'])
})

for (const conflict of ['stock', 'archived', 'state'] as const) {
  test(`${conflict} conflict shows server error and requires refresh before retry`, async ({ page }) => {
    const api = await setup(page, { conflict })
    await page.getByRole('button', { name: 'Confirm order', exact: true }).click()
    const dialog = page.getByRole('dialog')
    await dialog.getByRole('button', { name: 'Confirm and deduct stock' }).click()
    await expect(dialog.getByRole('alert')).toBeVisible()
    await expect(dialog.getByRole('button', { name: 'Confirm and deduct stock' })).toBeDisabled()
    expect(api.posts).toHaveLength(1)
    await dialog.getByRole('button', { name: 'Refresh order and availability' }).click()
    if (conflict === 'state') { await expect(dialog).toContainText('no longer available'); await expect(dialog.getByRole('button', { name: 'Confirm and deduct stock' })).toBeDisabled() }
    else await expect(dialog.getByRole('button', { name: 'Confirm and deduct stock' })).toBeEnabled()
    expect(api.posts).toHaveLength(1)
  })
}

test('lost confirmation response refreshes committed state without duplicate submission', async ({ page }) => {
  const api = await setup(page, { lostResponse: true })
  await page.getByRole('button', { name: 'Confirm order', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Confirm and deduct stock' }).click()
  await expect(dialog.getByRole('alert')).toContainText('result could not be confirmed')
  await dialog.getByRole('button', { name: 'Refresh order and availability' }).click()
  await expect(dialog).toContainText('Current state: CONFIRMED')
  await expect(dialog.getByRole('button', { name: 'Confirm and deduct stock' })).toBeDisabled()
  expect(api.posts).toEqual(['confirm'])
})

test('lost cancellation response and failed refresh keep recovery controls available', async ({ page }) => {
  const api = await setup(page, { status: 'CONFIRMED', lostResponse: true, readFailure: true })
  await page.getByRole('button', { name: 'Cancel order', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Cancel order permanently' }).click()
  await expect(dialog.getByRole('alert')).toContainText('result could not be confirmed')
  await dialog.getByRole('button', { name: 'Refresh order and availability' }).click()
  await expect(dialog.getByRole('button', { name: 'Cancel order permanently' })).toBeDisabled()
  await expect(dialog.getByRole('alert')).toBeVisible()
  api.allowReads()
  await dialog.getByRole('button', { name: 'Refresh order and availability' }).click()
  await expect(dialog).toContainText('Current state: CANCELLED')
  expect(api.posts).toEqual(['cancel'])
})

test('pending transition blocks duplicate requests and dialog dismissal', async ({ page }) => {
  let release!: () => void
  const hold = new Promise<void>(resolve => { release = resolve })
  const api = await setup(page, { hold })
  await page.getByRole('button', { name: 'Confirm order', exact: true }).click()
  const dialog = page.getByRole('dialog')
  await dialog.getByRole('button', { name: 'Confirm and deduct stock' }).click()
  await expect(dialog.getByRole('button', { name: 'Saving…' })).toBeDisabled()
  await expect(dialog.getByRole('button', { name: 'Close dialog' })).toBeDisabled()
  await expect.poll(() => api.posts.length).toBe(1)
  await dialog.locator('form').evaluate(form => (form as HTMLFormElement).requestSubmit())
  await page.keyboard.press('Escape')
  await expect(dialog).toBeVisible()
  release()
  await expect(dialog).toHaveCount(0)
  expect(api.posts).toEqual(['confirm'])
})
