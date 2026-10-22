import { useRef, useState, type FormEvent } from 'react'
import { Link, useNavigate, useParams, useSearchParams } from 'react-router-dom'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth'
import { Failure, Pager } from '../../components/Management'
import { ApiError } from '../../lib/api'

type Product = { id: string; sku: string; name: string; unitPrice: string; stockQuantity: number }
type Item = { id: string; productId: string; skuSnapshot: string; nameSnapshot: string; quantity: number; unitPriceSnapshot: string; lineTotal: string }
type Order = { id: string; orderNumber: string; customerName: string | null; reference: string | null; status: string; currency: string; total: string; createdBy: string; createdAt: string; confirmedAt: string | null; cancelledAt: string | null; items: Item[] }
type Page<T> = { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
type Line = { product: Product; quantity: string }
function cents(value: string) { const [whole, fraction = ''] = value.split('.'); return BigInt(whole) * 100n + BigInt(fraction.padEnd(2, '0')) }
function money(value: bigint) { return `${value / 100n}.${(value % 100n).toString().padStart(2, '0')}` }
function validQuantity(value: string) { return /^\d+$/.test(value) && Number(value) >= 1 && Number(value) <= 1000000 }

export function OrderCreate() {
  const { request } = useAuth(), cache = useQueryClient(), navigate = useNavigate()
  const [customerName, setCustomerName] = useState(''), [reference, setReference] = useState('')
  const [search, setSearch] = useState(''), [page, setPage] = useState(0), [lines, setLines] = useState<Line[]>([]), [validation, setValidation] = useState('')
  const submitting = useRef(false)
  const products = useQuery({ queryKey: ['products', 'order-picker', search, page], queryFn: ({ signal }) => request<Page<Product>>(`/products?${new URLSearchParams({ search, active: 'true', page: String(page), size: '10', sort: 'name,asc' })}`, { signal }), retry: false })
  const mutation = useMutation({ mutationFn: () => request<Order>('/orders', { method: 'POST', body: JSON.stringify({ customerName: customerName.trim() || null, reference: reference.trim() || null, items: lines.map(line => ({ productId: line.product.id, quantity: Number(line.quantity) })) }) }), retry: false,
    onSuccess: order => { cache.setQueryData(['order', order.id], order); void cache.invalidateQueries({ queryKey: ['orders'] }); navigate(`/orders/${order.id}`, { replace: true }) },
    onSettled: () => { submitting.current = false },
  })
  const uncertain = Boolean(mutation.error && (!(mutation.error instanceof ApiError) || mutation.error.status >= 500))
  const disabled = mutation.isPending || uncertain
  const total = lines.reduce((sum, line) => sum + (validQuantity(line.quantity) ? cents(line.product.unitPrice) * BigInt(line.quantity) : 0n), 0n)
  function submit(event: FormEvent) {
    event.preventDefault()
    if (disabled || submitting.current) return
    if (!lines.length || lines.some(line => !validQuantity(line.quantity))) { setValidation('Add 1–100 products with whole-number quantities from 1 to 1000000.'); return }
    setValidation(''); submitting.current = true; mutation.mutate()
  }
  return <main><Link to="/orders">← Orders</Link><h1>Create draft order</h1><p className="message">Drafts do not reserve or deduct stock. Prices below are estimates; the server saves the latest catalogue prices when you create the draft.</p>
    {validation && <p role="alert" className="error">{validation}</p>}
    {uncertain ? <div role="alert" className="error"><strong>The result could not be confirmed. Do not submit again yet.</strong><p>The draft may already exist. Inspect recent orders using your customer/reference before creating another.</p><p>Reference: {reference || 'Not provided'}</p><Link to="/orders">Inspect recent orders</Link></div> : <Failure error={mutation.error} />}
    <section className="panel"><h2>Select products</h2><label htmlFor="order-search">Search active products</label><input id="order-search" value={search} onChange={event => { setSearch(event.target.value); setPage(0) }} disabled={disabled} />
      <Failure error={products.error} />{products.isPending && <p role="status">Loading products…</p>}
      {products.data && <><ul>{products.data.content.map(product => <li key={product.id}><strong>{product.name}</strong> · {product.sku} · {product.unitPrice} · Stock {product.stockQuantity} <button type="button" className="secondary" disabled={disabled || lines.length >= 100 || lines.some(line => line.product.id === product.id)} onClick={() => setLines(current => [...current, { product, quantity: '1' }])}>Add {product.sku}</button></li>)}</ul>{!products.data.content.length && <p>No active products found.</p>}<Pager {...products.data} onChange={setPage} pending={products.isFetching || disabled} /></>}
    </section>
    <form onSubmit={submit} className="panel"><h2>Draft details</h2><label htmlFor="order-customer">Customer name (optional)</label><input id="order-customer" maxLength={160} value={customerName} onChange={event => setCustomerName(event.target.value)} disabled={disabled} /><label htmlFor="order-reference">Reference (optional)</label><input id="order-reference" maxLength={160} value={reference} onChange={event => setReference(event.target.value)} disabled={disabled} />
      {lines.map(line => <fieldset key={line.product.id} disabled={disabled}><legend>{line.product.name} · {line.product.sku}</legend><p>Catalogue unit price: {line.product.unitPrice}</p><label htmlFor={`quantity-${line.product.id}`}>Quantity for {line.product.sku}</label><input id={`quantity-${line.product.id}`} type="number" min="1" max="1000000" step="1" required value={line.quantity} onChange={event => setLines(current => current.map(item => item.product.id === line.product.id ? { ...item, quantity: event.target.value } : item))} /><p>Estimated line total: {validQuantity(line.quantity) ? money(cents(line.product.unitPrice) * BigInt(line.quantity)) : '—'}</p><button type="button" className="secondary" onClick={() => setLines(current => current.filter(item => item.product.id !== line.product.id))}>Remove {line.product.sku}</button></fieldset>)}
      <p role="status">Estimated total: <strong>{money(total)}</strong> (catalogue currency; authoritative currency and total appear after saving).</p><button disabled={disabled}>{mutation.isPending ? 'Creating draft…' : 'Create draft'}</button>
    </form></main>
}

export function OrderList() {
  const { request } = useAuth(), [params, setParams] = useSearchParams()
  const page = Number(params.get('page') ?? 0), status = params.get('status') ?? '', sort = params.get('sort') ?? 'createdAt,desc'
  const from = params.get('createdFrom') ?? '', to = params.get('createdTo') ?? ''
  function change(key: string, value: string) { setParams(current => { const next = new URLSearchParams(current); next.delete('page'); if (value) next.set(key, value); else next.delete(key); return next }) }
  const query = useQuery({ queryKey: ['orders', params.toString()], queryFn: ({ signal }) => request<Page<Order>>(`/orders?${new URLSearchParams({ ...Object.fromEntries(params), page: String(page), size: '20', sort })}`, { signal }), retry: false })
  return <main><div className="page-heading"><h1>Orders</h1><Link className="button" to="/orders/new">Create draft order</Link></div><p className="muted">Drafts hold saved price snapshots, not stock reservations.</p><section className="panel"><label htmlFor="order-status">Status</label><select id="order-status" value={status} onChange={event => change('status', event.target.value)}><option value="">All statuses</option>{['DRAFT', 'CONFIRMED', 'CANCELLED'].map(value => <option key={value}>{value}</option>)}</select><label htmlFor="order-from">Created from (inclusive ISO timestamp)</label><input id="order-from" placeholder="2026-01-01T00:00:00Z" value={from} onChange={event => change('createdFrom', event.target.value)} /><label htmlFor="order-to">Created before (exclusive ISO timestamp)</label><input id="order-to" placeholder="2027-01-01T00:00:00Z" value={to} onChange={event => change('createdTo', event.target.value)} /><label htmlFor="order-sort">Sort orders</label><select id="order-sort" value={sort} onChange={event => change('sort', event.target.value)}><option value="createdAt,desc">Newest first</option><option value="createdAt,asc">Oldest first</option><option value="total,desc">Total: high to low</option><option value="total,asc">Total: low to high</option><option value="orderNumber,asc">Order number</option></select>
    {query.isPending && <p role="status">Loading orders…</p>}<Failure error={query.error} />{query.isError && <button onClick={() => void query.refetch()}>Retry orders</button>}
    {query.data && !query.isError && <>{query.data.content.length ? <div className="table-scroll"><table><thead><tr><th>Order</th><th>Customer / reference</th><th>Status</th><th>Total</th><th>Created</th></tr></thead><tbody>{query.data.content.map(order => <tr key={order.id}><td><Link to={`/orders/${order.id}`}>{order.orderNumber}</Link></td><td>{order.customerName ?? 'Not provided'}<p>{order.reference}</p></td><td>{order.status}</td><td>{order.currency} {order.total}</td><td>{new Date(order.createdAt).toLocaleString()}</td></tr>)}</tbody></table></div> : <p>No orders match these filters.</p>}<Pager {...query.data} onChange={value => setParams(current => { const next = new URLSearchParams(current); next.set('page', String(value)); return next })} pending={query.isFetching} /></>}
    </section></main>
}

export function OrderDetail() {
  const { id } = useParams(), { request } = useAuth()
  const query = useQuery({ queryKey: ['order', id], queryFn: ({ signal }) => request<Order>(`/orders/${id}`, { signal }), retry: false })
  const order = query.data
  return <main><Link to="/orders">← Orders</Link>{query.isPending && <p role="status">Loading order…</p>}<Failure error={query.error} />{query.isError && <button onClick={() => void query.refetch()}>Retry order</button>}{order && <><h1>{order.orderNumber}</h1><section className="panel"><h2>{order.status} order</h2><p>Customer: {order.customerName ?? 'Not provided'}</p><p>Reference: {order.reference ?? 'Not provided'}</p><p className="message">Drafts do not reserve or deduct stock. Saved quantities and price snapshots are read-only. Confirmation and cancellation arrive in Phase 6.</p><div className="table-scroll"><table><thead><tr><th>Product snapshot</th><th>Quantity</th><th>Unit price</th><th>Line total</th></tr></thead><tbody>{order.items.map(item => <tr key={item.id}><td>{item.nameSnapshot}<p>{item.skuSnapshot}</p></td><td>{item.quantity}</td><td>{order.currency} {item.unitPriceSnapshot}</td><td>{order.currency} {item.lineTotal}</td></tr>)}</tbody></table></div><p><strong>Total: {order.currency} {order.total}</strong></p></section><section className="panel"><h2>Order timeline</h2><ol><li>Draft created: <time dateTime={order.createdAt}>{new Date(order.createdAt).toLocaleString()}</time> · Actor: <code>{order.createdBy}</code></li>{order.confirmedAt && <li>Confirmed: {new Date(order.confirmedAt).toLocaleString()}</li>}{order.cancelledAt && <li>Cancelled: {new Date(order.cancelledAt).toLocaleString()}</li>}</ol></section></>}</main>
}
