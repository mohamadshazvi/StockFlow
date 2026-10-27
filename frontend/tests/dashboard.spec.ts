import { test, expect, type Page } from '@playwright/test'

async function setup(page: Page, options: { role?: 'ADMIN' | 'STAFF'; empty?: boolean; failSummary?: boolean; count?: number } = {}) {
  const user = { id: 'dashboard-user', email: 'dashboard@example.test', role: options.role ?? 'ADMIN', active: true }
  const products = Array.from({ length: options.empty ? 0 : options.count ?? 1 }, (_, i) => ({ id: `dashboard-product-${i}`, sku: `DASH-${i}`, name: `Dashboard product ${i}`, unitPrice: '12.50', stockQuantity: 0, lowStockThreshold: 2, active: true, supplierId: null, description: null, createdAt: '2026-10-20T00:00:00Z' }))
  const orders: { id: string; orderNumber: string; status: string; total: string; currency: string; createdAt: string; createdBy: string; customerName: string | null; reference: string | null; confirmedBy: string | null; confirmedAt: string | null; cancelledBy: string | null; cancelledAt: string | null; items: { id: string; productId: string; skuSnapshot: string; nameSnapshot: string; unitPriceSnapshot: string; quantity: number; lineTotal: string }[] }[] = []
  const movements: Record<string, unknown>[] = []
  let failSummary = Boolean(options.failSummary)
  await page.route('**/api/v1/**', async route => {
    const request = route.request(), url = new URL(request.url()), path = url.pathname
    if (path.endsWith('/auth/login')) return route.fulfill({ json: { accessToken: 'mock-token', expiresIn: 900, user } })
    if (path.endsWith('/auth/me')) return route.fulfill({ json: user })
    if (path === '/api/v1/dashboard/summary') return failSummary ? route.fulfill({ status: 503, json: { message: 'Summary temporarily unavailable.', fieldErrors: [] } }) : route.fulfill({ json: { activeProductCount: products.length, lowStockCount: products.filter(p => p.stockQuantity <= p.lowStockThreshold).length, draftOrderCount: orders.filter(o => o.status === 'DRAFT').length, confirmedOrderValue: orders.filter(o => o.status === 'CONFIRMED').reduce((sum, order) => sum + Number(order.total), 0).toFixed(2), currency: 'LKR' } })
    if (path === '/api/v1/suppliers') return route.fulfill({ json: { content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 } })
    if (path === '/api/v1/products') {
      const content = url.searchParams.get('lowStock') === 'true' ? products.filter(p => p.stockQuantity <= p.lowStockThreshold) : products
      const index = Number(url.searchParams.get('page') ?? 0), size = Number(url.searchParams.get('size') ?? 20)
      return route.fulfill({ json: { content: content.slice(index * size, (index + 1) * size), page: index, size, totalElements: content.length, totalPages: Math.ceil(content.length / size) } })
    }
    if (path === '/api/v1/stock-movements' && request.method() === 'POST') {
      const input = request.postDataJSON(), product = products.find(p => p.id === input.productId)!
      product.stockQuantity += input.quantityDelta
      const movement = { ...input, id: `receipt-${movements.length}`, orderId: null, createdBy: user.id, createdAt: new Date().toISOString() }; movements.push(movement)
      return route.fulfill({ status: 201, json: movement })
    }
    if (path.endsWith('/stock-movements')) return route.fulfill({ json: { content: movements, page: 0, size: 20, totalElements: movements.length, totalPages: movements.length ? 1 : 0 } })
    const product = products.find(p => path === `/api/v1/products/${p.id}`)
    if (product) return route.fulfill({ json: product })
    if (path === '/api/v1/orders' && request.method() === 'POST') {
      const input = request.postDataJSON(), product = products.find(p => p.id === input.items[0].productId)!, quantity = input.items[0].quantity
      const order = { id: 'dashboard-order', orderNumber: 'SF-00000007', status: 'DRAFT', total: (quantity * 12.5).toFixed(2), currency: 'LKR', createdAt: new Date().toISOString(), createdBy: user.id, customerName: input.customerName, reference: input.reference, confirmedBy: null, confirmedAt: null, cancelledBy: null, cancelledAt: null, items: [{ id: 'item', productId: product.id, skuSnapshot: product.sku, nameSnapshot: product.name, unitPriceSnapshot: product.unitPrice, quantity, lineTotal: (quantity * 12.5).toFixed(2) }] }; orders.push(order)
      return route.fulfill({ status: 201, json: order })
    }
    if (path === '/api/v1/orders') return route.fulfill({ json: { content: orders, page: 0, size: 5, totalElements: orders.length, totalPages: orders.length ? 1 : 0 } })
    const order = orders.find(o => path.startsWith(`/api/v1/orders/${o.id}`))
    if (order) {
      const confirm = path.endsWith('/confirm'), cancel = path.endsWith('/cancel')
      if ((confirm && order.status === 'DRAFT') || (cancel && order.status === 'CONFIRMED')) {
        const product = products.find(p => p.id === order.items[0].productId)!, delta = (confirm ? -1 : 1) * order.items[0].quantity
        product.stockQuantity += delta
        movements.push({ id: `effect-${movements.length}`, productId: product.id, type: confirm ? 'ORDER_CONFIRMATION' : 'ORDER_CANCELLATION', quantityDelta: delta, reason: null, reference: order.orderNumber, orderId: order.id, createdBy: user.id, createdAt: new Date().toISOString() })
        order.status = confirm ? 'CONFIRMED' : 'CANCELLED'
        if (confirm) { order.confirmedBy = user.id; order.confirmedAt = new Date().toISOString() } else { order.cancelledBy = user.id; order.cancelledAt = new Date().toISOString() }
      }
      return route.fulfill({ json: order })
    }
    return route.fulfill({ status: 404, json: { message: 'Not found', fieldErrors: [] } })
  })
  await page.goto('/login')
  await page.getByLabel('Email address').fill(user.email)
  await page.getByLabel('Password', { exact: true }).fill('synthetic-test-password')
  await page.getByRole('button', { name: 'Sign in', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'Welcome to StockFlow.' })).toBeVisible()
  return { recover: () => { failSummary = false } }
}

function metric(page: Page, label: string) { return page.locator('.metric-grid > div').filter({ has: page.locator('dt', { hasText: label }) }).locator('dd') }
async function dashboard(page: Page) { await page.getByRole('link', { name: 'Dashboard', exact: true }).click(); await expect(page.getByRole('heading', { name: 'Business summary' })).toBeVisible() }

test('receipt -> draft -> confirmation -> dashboard -> cancellation updates metrics and history', async ({ page }) => {
  await setup(page)
  await expect(metric(page, 'Low-stock products')).toHaveText('1')
  await page.getByRole('link', { name: 'Dashboard product 0', exact: true }).click()
  await page.getByRole('button', { name: 'Receive stock', exact: true }).click()
  await page.getByRole('dialog').getByLabel('Quantity received').fill('5')
  await page.getByRole('dialog').getByRole('button', { name: 'Record movement' }).click()
  await expect(page.getByRole('dialog')).toHaveCount(0)
  await dashboard(page)
  await expect(metric(page, 'Low-stock products')).toHaveText('0')
  await page.getByRole('link', { name: 'Create draft order', exact: true }).first().click()
  await page.getByRole('button', { name: 'Add DASH-0' }).click()
  await page.getByLabel('Quantity for DASH-0', { exact: true }).fill('3')
  await page.getByRole('button', { name: 'Create draft', exact: true }).click()
  await expect(page.getByRole('heading', { name: 'DRAFT order' })).toBeVisible()
  await dashboard(page)
  await expect(metric(page, 'Draft orders')).toHaveText('1')
  await page.getByRole('link', { name: 'SF-00000007' }).click()
  await page.getByRole('button', { name: 'Confirm order', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Confirm and deduct stock' }).click()
  await expect(page.getByRole('heading', { name: 'CONFIRMED order' })).toBeVisible()
  await dashboard(page)
  await expect(metric(page, 'Confirmed order value')).toHaveText('LKR 37.50')
  await expect(metric(page, 'Draft orders')).toHaveText('0')
  await expect(metric(page, 'Low-stock products')).toHaveText('1')
  await page.getByRole('link', { name: 'Dashboard product 0', exact: true }).click()
  await expect(page.getByRole('cell', { name: '-3', exact: true })).toBeVisible()
  await dashboard(page)
  await page.getByRole('link', { name: 'SF-00000007' }).click()
  await page.getByRole('button', { name: 'Cancel order', exact: true }).click()
  await page.getByRole('dialog').getByRole('button', { name: 'Cancel order permanently' }).click()
  await expect(page.getByRole('heading', { name: 'CANCELLED order' })).toBeVisible()
  await dashboard(page)
  await expect(metric(page, 'Confirmed order value')).toHaveText('LKR 0.00')
  await expect(metric(page, 'Low-stock products')).toHaveText('0')
})

test('staff dashboard uses paginated low-stock endpoint and preserves URL state', async ({ page }) => {
  await setup(page, { role: 'STAFF', count: 6 })
  await expect(metric(page, 'Active products')).toHaveText('6')
  await expect(page.getByRole('link', { name: 'Users', exact: true })).toHaveCount(0)
  await expect(page.getByText('Ask an administrator to replenish stock.', { exact: false })).toBeVisible()
  await page.getByRole('button', { name: 'Next', exact: true }).click()
  await expect(page).toHaveURL(/lowPage=1/)
  await expect(page.getByRole('link', { name: 'Dashboard product 5', exact: true })).toBeVisible()
  await page.getByRole('link', { name: 'Dashboard product 5', exact: true }).click()
  await expect(page.getByRole('button', { name: 'Receive stock', exact: true })).toHaveCount(0)
  await page.goBack()
  await expect(page).toHaveURL(/lowPage=1/)
})

test('summary failure does not hide lists and retry recovers', async ({ page }) => {
  const api = await setup(page, { failSummary: true })
  await expect(page.getByRole('alert')).toContainText('Summary temporarily unavailable')
  await expect(page.getByRole('link', { name: 'Dashboard product 0', exact: true })).toBeVisible()
  api.recover()
  await page.getByRole('button', { name: 'Retry summary' }).click()
  await expect(metric(page, 'Active products')).toHaveText('1')
})

for (const width of [360, 768, 1440]) {
  test(`dashboard reflows at ${width}px with keyboard navigation`, async ({ page }, testInfo) => {
    await page.setViewportSize({ width, height: 900 })
    await setup(page)
    await expect(page.getByText('No orders yet.', { exact: false })).toBeVisible()
    await expect(metric(page, 'Confirmed order value')).toHaveText('LKR 0.00')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
    if (width < 900) {
      await page.getByRole('button', { name: 'Menu', exact: true }).focus()
      await page.keyboard.press('Enter')
      await expect(page.getByRole('navigation')).toBeVisible()
      await page.keyboard.press('Tab')
      await page.keyboard.press('Escape')
      await expect(page.getByRole('button', { name: 'Menu', exact: true })).toBeFocused()
      await expect(page.getByRole('navigation')).not.toBeVisible()
    }
    await page.getByRole('link', { name: 'Skip to main content' }).focus()
    await page.keyboard.press('Enter')
    await expect(page.locator('main')).toBeFocused()
    await expect(page.getByRole('link', { name: 'Skip to main content' })).not.toBeFocused()
    await page.evaluate(() => window.scrollTo(0, 0))
    await page.screenshot({ path: testInfo.outputPath(`dashboard-${width}.png`), fullPage: true })
    await page.getByRole('link', { name: 'Dashboard product 0', exact: true }).click()
    await page.getByRole('button', { name: 'Receive stock', exact: true }).click()
    const bounds = await page.getByRole('dialog').boundingBox()
    expect(bounds!.x).toBeGreaterThanOrEqual(0)
    expect(bounds!.x + bounds!.width).toBeLessThanOrEqual(width)
    await page.keyboard.press('Escape')
    if (width < 900) await page.getByRole('button', { name: 'Menu', exact: true }).click()
    await page.getByRole('link', { name: 'Orders', exact: true }).click()
    await page.getByRole('link', { name: 'Create draft order', exact: true }).click()
    await page.getByRole('button', { name: 'Add DASH-0' }).click()
    await page.getByLabel('Quantity for DASH-0', { exact: true }).fill('2')
    expect(await page.evaluate(() => document.documentElement.scrollWidth <= innerWidth)).toBe(true)
  })
}

test('two-times CSS zoom reflows dashboard and rendered text meets contrast thresholds', async ({ page }) => {
  await page.setViewportSize({ width: 1440, height: 1000 })
  await setup(page)
  const contrast = await page.locator('.metric-grid dt, .metric-grid dd, .muted, .sidebar nav a, main button:not(:disabled)').evaluateAll(elements => {
    function rgb(value: string) { return (value.match(/[\d.]+/g) ?? []).map(Number) }
    function luminance(color: number[]) { return color.slice(0, 3).map(v => { const c = v / 255; return c <= .04045 ? c / 12.92 : ((c + .055) / 1.055) ** 2.4 }).reduce((sum, c, i) => sum + c * [.2126, .7152, .0722][i], 0) }
    return elements.filter(element => element.getClientRects().length).map(element => {
      const style = getComputedStyle(element); let parent: Element | null = element, background = [248, 250, 252]
      while (parent) { const color = rgb(getComputedStyle(parent).backgroundColor); if (color.length === 3 || color[3] === 1) { background = color; break } parent = parent.parentElement }
      const a = luminance(rgb(style.color)), b = luminance(background)
      return { text: element.textContent?.trim(), ratio: (Math.max(a, b) + .05) / (Math.min(a, b) + .05) }
    })
  })
  for (const item of contrast) expect(item.ratio, item.text).toBeGreaterThanOrEqual(4.5)
  await page.evaluate(() => { document.documentElement.style.zoom = '2' })
  expect(await page.evaluate(() => document.documentElement.scrollWidth <= document.documentElement.clientWidth)).toBe(true)
  await expect(page.getByRole('button', { name: 'Refresh dashboard', exact: true })).toBeEnabled()
})

test('unsaved draft guards link, browser back and signout; session reload requires login', async ({ page }) => {
  const runWithDialog = async (action: () => Promise<void>, mode: 'accept' | 'dismiss' = 'dismiss') => {
    const handled = page.waitForEvent('dialog').then(dialog => dialog[mode]())
    await action()
    await handled
  }
  await setup(page)
  await page.getByRole('link', { name: 'Create draft order', exact: true }).first().click()
  await page.getByLabel('Reference (optional)', { exact: true }).fill('KEEP-INPUT')
  await runWithDialog(() => page.getByRole('link', { name: '← Orders', exact: true }).click())
  await expect(page.getByLabel('Reference (optional)', { exact: true })).toHaveValue('KEEP-INPUT')
  await runWithDialog(() => page.goBack())
  await expect(page).toHaveURL(/orders\/new/)
  await runWithDialog(() => page.getByRole('button', { name: 'Sign out', exact: true }).click())
  await expect(page.getByLabel('Reference (optional)', { exact: true })).toHaveValue('KEEP-INPUT')
  await runWithDialog(() => page.getByRole('link', { name: '← Orders', exact: true }).click(), 'accept')
  await expect(page).toHaveURL(/\/orders$/)
  await page.reload()
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
})

test('stock dialog traps keyboard focus and returns focus to its trigger on escape', async ({ page }) => {
  await setup(page)
  await page.getByRole('link', { name: 'Dashboard product 0', exact: true }).click()
  const trigger = page.getByRole('button', { name: 'Receive stock', exact: true })
  await trigger.click()
  const dialog = page.getByRole('dialog')
  for (let i = 0; i < 12; i++) { await page.keyboard.press('Tab'); expect(await dialog.evaluate(element => element.contains(document.activeElement))).toBe(true) }
  await page.keyboard.press('Escape')
  await expect(dialog).toHaveCount(0)
  await expect(trigger).toBeFocused()
})

test('large dashboard money is formatted without floating-point rounding', async ({ page }) => {
  await setup(page)
  await page.route('**/api/v1/dashboard/summary', route => route.fulfill({ json: { activeProductCount: 1, lowStockCount: 1, draftOrderCount: 0, confirmedOrderValue: '19999999999980000.01', currency: 'LKR' } }))
  await page.getByRole('button', { name: 'Refresh dashboard', exact: true }).click()
  await expect(metric(page, 'Confirmed order value')).toHaveText('LKR 19,999,999,999,980,000.01')
})

test('forced session invalidation is not blocked by unsaved form warnings', async ({ page }) => {
  await setup(page)
  await page.getByRole('link', { name: 'Create draft order', exact: true }).first().click()
  await page.getByLabel('Reference (optional)', { exact: true }).fill('UNSAVED')
  await page.route('**/api/v1/products?*', route => route.fulfill({ status: 401, json: { message: 'Session revoked.', fieldErrors: [] } }))
  await page.getByLabel('Search active products').fill('trigger-request')
  await expect(page.getByRole('heading', { name: 'Welcome back' })).toBeVisible()
  await expect(page.getByText('Your session is no longer valid. Please sign in again.')).toBeVisible()
})
