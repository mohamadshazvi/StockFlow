import { Link, useSearchParams } from 'react-router-dom'
import { useQuery } from '@tanstack/react-query'
import { useAuth } from '../../auth'
import { Failure, Pager, StatusBadge } from '../../components/Management'
import { formatMoney, formatDate } from '../../lib/format'
import './dashboard.css'

type Summary = { activeProductCount: number; lowStockCount: number; draftOrderCount: number; confirmedOrderValue: string; currency: string }
type Page<T> = { content: T[]; page: number; totalPages: number; totalElements: number }
type Product = { id: string; name: string; sku: string; stockQuantity: number; lowStockThreshold: number }
type Order = { id: string; orderNumber: string; customerName: string | null; status: string; total: string; currency: string; createdAt: string }

export function Dashboard() {
  const { request, session } = useAuth(), [params, setParams] = useSearchParams()
  const rawPage = params.get('lowPage') ?? '0', page = /^\d+$/.test(rawPage) && Number.isSafeInteger(Number(rawPage)) ? Number(rawPage) : 0
  const summary = useQuery({ queryKey: ['dashboard', 'summary'], queryFn: ({ signal }) => request<Summary>('/dashboard/summary', { signal }), retry: false })
  const recent = useQuery({ queryKey: ['orders', 'dashboard-recent'], queryFn: ({ signal }) => request<Page<Order>>('/orders?page=0&size=5&sort=createdAt,desc', { signal }), retry: false })
  const low = useQuery({ queryKey: ['products', 'dashboard-low', page], queryFn: ({ signal }) => request<Page<Product>>(`/products?active=true&lowStock=true&page=${page}&size=5&sort=stockQuantity,asc`, { signal }), retry: false })
  const refreshing = summary.isFetching || recent.isFetching || low.isFetching
  function refresh() { void summary.refetch(); void recent.refetch(); void low.refetch() }
  return <main className="dashboard"><div className="page-heading"><div><p className="eyebrow">OPERATIONS OVERVIEW</p><h1>Welcome to StockFlow.</h1><p className="muted">Current inventory and order activity for your team.</p></div><button className="secondary" disabled={refreshing} onClick={refresh}>{refreshing ? 'Refreshing dashboard…' : 'Refresh dashboard'}</button></div>
    <div className="form-actions"><Link className="button-link" to="/orders/new">Create draft order</Link><Link to="/products">Open product catalogue →</Link></div>
    <section aria-labelledby="summary-title"><h2 id="summary-title">Business summary</h2><p className="muted">Confirmed order value is not collected revenue. Drafts do not reserve stock.</p>
      {summary.isPending && <p role="status">Loading summary…</p>}<Failure error={summary.error} />
      {summary.isError && <button className="secondary" onClick={() => void summary.refetch()}>Retry summary</button>}
      {summary.data && !summary.isError && <dl className="metric-grid" aria-live="polite"><div><dt>Active products</dt><dd>{summary.data.activeProductCount}</dd></div><div><dt>Low-stock products</dt><dd>{summary.data.lowStockCount}</dd></div><div><dt>Draft orders</dt><dd>{summary.data.draftOrderCount}</dd></div><div><dt>Confirmed order value</dt><dd>{formatMoney(summary.data.confirmedOrderValue, summary.data.currency)}</dd></div></dl>}
    </section>
    <section className="panel" aria-labelledby="recent-title"><div className="page-heading"><h2 id="recent-title">Recent orders</h2><Link to="/orders">View all orders</Link></div>
      {recent.isPending && <p role="status">Loading recent orders…</p>}<Failure error={recent.error} />{recent.isError && <button className="secondary" onClick={() => void recent.refetch()}>Retry recent orders</button>}
      {recent.data && !recent.isError && (recent.data.content.length ? <div className="table-scroll" role="region" aria-label="Recent orders table" tabIndex={0}><table><caption className="sr-only">Five most recently created orders</caption><thead><tr><th scope="col">Order / customer</th><th scope="col">Status</th><th scope="col" className="numeric">Total</th><th scope="col">Created</th></tr></thead><tbody>{recent.data.content.map(order => <tr key={order.id}><th scope="row"><Link to={`/orders/${order.id}`}>{order.orderNumber}</Link><p className="muted">{order.customerName ?? 'Customer not provided'}</p></th><td><StatusBadge status={order.status} /></td><td className="numeric">{formatMoney(order.total, order.currency)}</td><td><time dateTime={order.createdAt}>{formatDate(order.createdAt)}</time></td></tr>)}</tbody></table></div> : <p>No orders yet. <Link to="/orders/new">Create your first draft</Link> to get started.</p>)}
    </section>
    <section className="panel" aria-labelledby="low-title"><div className="page-heading"><h2 id="low-title">Low-stock products</h2><Link to="/products?lowStock=true">View low-stock catalogue</Link></div><p className="muted">Active products at or below their threshold, lowest stock first.{session?.user.role === 'ADMIN' ? ' Open a product to receive stock.' : ' Ask an administrator to replenish stock.'}</p>
      {low.isPending && <p role="status">Loading low-stock products…</p>}<Failure error={low.error} />{low.isError && <button className="secondary" onClick={() => void low.refetch()}>Retry low-stock products</button>}
      {low.data && !low.isError && <>{low.data.content.length ? <div className="table-scroll" role="region" aria-label="Low-stock products table" tabIndex={0}><table><caption className="sr-only">Active products needing replenishment</caption><thead><tr><th scope="col">Product</th><th scope="col">SKU</th><th scope="col" className="numeric">Available</th><th scope="col" className="numeric">Threshold</th></tr></thead><tbody>{low.data.content.map(product => <tr key={product.id}><th scope="row"><Link to={`/products/${product.id}`}>{product.name}</Link></th><td>{product.sku}</td><td className="numeric">{product.stockQuantity}</td><td className="numeric">{product.lowStockThreshold}</td></tr>)}</tbody></table></div> : <p>{page > 0 ? 'No low-stock products on this page. Return to the first page or refresh.' : 'No low-stock products. Active products are above their thresholds, or the catalogue is empty.'}</p>}<Pager {...low.data} page={page} onChange={value => setParams(current => { const next = new URLSearchParams(current); next.set('lowPage', String(value)); return next })} pending={low.isFetching} />{page > 0 && !low.data.content.length && <button className="secondary" onClick={() => setParams({})}>First low-stock page</button>}</>}
    </section><p className="muted">Metrics and lists are separate committed reads and can briefly differ during concurrent updates. Refresh to reconcile. Reloading this page requires signing in again.</p>
  </main>
}
