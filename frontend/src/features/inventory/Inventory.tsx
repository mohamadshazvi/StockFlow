import { useState, type FormEvent } from 'react'
import { useMutation, useQuery, useQueryClient } from '@tanstack/react-query'
import { useAuth } from '../../auth'
import { Failure, Modal, Pager } from '../../components/Management'
import { ApiError } from '../../lib/api'
import { useDirty } from '../../lib/useDirty'
import './inventory.css'

type Product = { id: string; name: string; stockQuantity: number; active: boolean }
type Movement = { id: string; productId: string; type: string; quantityDelta: number; reason: string | null; reference: string | null; orderId: string | null; createdBy: string; createdAt: string }
type MovementPage = { content: Movement[]; page: number; size: number; totalElements: number; totalPages: number }
type ManualType = 'RECEIPT' | 'ADJUSTMENT'
const typeNames: Record<string, string> = { RECEIPT: 'Receipt', ADJUSTMENT: 'Adjustment', ORDER_CONFIRMATION: 'Order confirmed', ORDER_CANCELLATION: 'Order cancelled' }

function StockDialog({ product, type, close, recorded }: { product: Product; type: ManualType; close: () => void; recorded: (movement: Movement) => void }) {
  const { request } = useAuth(), cache = useQueryClient()
  const [quantity, setQuantity] = useState(''), [reason, setReason] = useState(''), [reference, setReference] = useState('')
  useDirty(Boolean(quantity || reason || reference))
  const [validation, setValidation] = useState('')
  const [checked, setChecked] = useState(false)
  const delta = /^-?\d+$/.test(quantity) ? Number(quantity) : NaN
  const validQuantity = Number.isInteger(delta) && delta >= -2147483648 && delta <= 2147483647 && (type === 'RECEIPT' ? delta > 0 : delta !== 0)
  const preview = validQuantity ? product.stockQuantity + delta : null
  const mutation = useMutation({
    mutationFn: () => request<Movement>('/stock-movements', {
      method: 'POST', body: JSON.stringify({ productId: product.id, type, quantityDelta: delta, reason: reason.trim() || null, reference: reference.trim() || null }),
    }),
    retry: false,
    onSuccess: movement => {
      recorded(movement)
      // Never infer an authoritative stock balance from a client-side preview.
      void Promise.all([
        cache.invalidateQueries({ queryKey: ['product', product.id] }),
        cache.invalidateQueries({ queryKey: ['products'] }),
        cache.invalidateQueries({ queryKey: ['movements', product.id] }),
        cache.invalidateQueries({ queryKey: ['dashboard'] }),
      ])
    },
    onError: () => {
      void cache.invalidateQueries({ queryKey: ['product', product.id] })
      void cache.invalidateQueries({ queryKey: ['movements', product.id] })
    },
  })
  const uncertain = Boolean(mutation.error && (!(mutation.error instanceof ApiError) || mutation.error.status >= 500))
  function submit(event: FormEvent) {
    event.preventDefault()
    if (mutation.isPending || uncertain || !product.active) return
    if (!validQuantity) { setValidation(type === 'RECEIPT' ? 'Enter a positive whole number up to 2147483647.' : 'Enter a nonzero signed whole number within integer bounds.'); return }
    if (type === 'ADJUSTMENT' && !reason.trim()) { setValidation('A reason is required for stock adjustments.'); return }
    if (preview !== null && (preview < 0 || preview > 2147483647)) { setValidation('The preview is outside the supported stock range. Refresh availability or change the quantity.'); return }
    setValidation('')
    mutation.mutate()
  }
  function dismiss() {
    if (mutation.isPending) return
    if (uncertain || (!quantity && !reason && !reference) || window.confirm('Close this stock form? Unsaved values will be discarded.')) close()
  }
  return <Modal title={type === 'RECEIPT' ? 'Receive stock' : 'Adjust stock'} close={dismiss} pending={mutation.isPending}>
    <p><strong>{product.name}</strong></p>
    <p className="muted">Current stock: <strong>{product.stockQuantity}</strong>. {type === 'RECEIPT' ? 'Receipts add units to inventory.' : 'Use a negative quantity to remove units or a positive quantity to add them.'}</p>
    {!product.active && <p className="error" role="alert">This product is archived. Manual stock changes are unavailable.</p>}
    {validation && <p className="error" role="alert">{validation}</p>}
    {uncertain ? <div className="error" role="alert"><strong>The result could not be confirmed.</strong><p>Do not submit again yet. Close this form and inspect movement history and the current balance. This request may already have been recorded.</p><p>Your reference: {reference || 'Not provided'}</p><button className="secondary" onClick={close}>Close and inspect history</button></div> : <Failure error={mutation.error} />}
    <form onSubmit={submit}>
      <label htmlFor="movement-quantity">{type === 'RECEIPT' ? 'Quantity received' : 'Signed adjustment quantity'}</label>
      <input id="movement-quantity" inputMode={type === 'RECEIPT' ? 'numeric' : 'text'} value={quantity} onChange={event => setQuantity(event.target.value)} maxLength={11} required disabled={mutation.isPending || uncertain} aria-describedby="movement-preview" />
      <p id="movement-preview" role="status" className="message">{uncertain ? 'Result unknown. Inspect history before making another change.' : <>Estimated resulting stock: <strong>{preview === null ? '—' : preview}</strong>. The server validates the latest balance before saving.</>}</p>
      <label htmlFor="movement-reason">Reason {type === 'ADJUSTMENT' ? '(required)' : '(optional)'}</label>
      <textarea id="movement-reason" value={reason} onChange={event => setReason(event.target.value)} maxLength={500} required={type === 'ADJUSTMENT'} disabled={mutation.isPending || uncertain} />
      <label htmlFor="movement-reference">Reference (optional)</label>
      <input id="movement-reference" value={reference} onChange={event => setReference(event.target.value)} maxLength={160} disabled={mutation.isPending || uncertain} placeholder="Delivery or adjustment reference" />
      <p className="muted">This creates an immutable history entry. Correct mistakes with a new adjustment, not by editing history.</p>
      {uncertain && <p className="muted">Resubmission is disabled until you close this form and reconcile the outcome.</p>}
      <div className="form-actions"><button disabled={mutation.isPending || uncertain || !product.active}>{mutation.isPending ? 'Recording…' : 'Record movement'}</button><button type="button" className="secondary" disabled={mutation.isPending} onClick={dismiss}>Cancel</button><button type="button" className="secondary" disabled={mutation.isPending} onClick={() => { void cache.invalidateQueries({ queryKey: ['product', product.id] }); setChecked(true) }}>Refresh availability</button></div>
      {checked && <p className="muted" role="status">Availability refresh requested. Check the current stock above before submitting.</p>}
    </form>
  </Modal>
}

export function InventoryPanel({ product }: { product: Product }) {
  const { request, session } = useAuth()
  const [page, setPage] = useState(0), [sort, setSort] = useState('createdAt,desc')
  const [dialog, setDialog] = useState<ManualType | null>(null), [notice, setNotice] = useState('')
  const history = useQuery({ queryKey: ['movements', product.id, page, sort], queryFn: ({ signal }) => request<MovementPage>(`/products/${product.id}/stock-movements?${new URLSearchParams({ page: String(page), size: '20', sort })}`, { signal }), retry: false })
  return <section className="panel" aria-labelledby="movement-title">
    <div className="page-heading"><div><h2 id="movement-title">Stock movement history</h2><p className="muted">Every recorded stock change, with its actor and reference.</p></div>{session?.user.role === 'ADMIN' && product.active && <div className="form-actions"><button onClick={() => { setNotice(''); setDialog('RECEIPT') }}>Receive stock</button><button className="secondary" onClick={() => { setNotice(''); setDialog('ADJUSTMENT') }}>Adjust stock</button></div>}</div>
    {!product.active && <p className="message">This product is archived. History remains available; manual stock changes are disabled.</p>}
    {notice && <p className="message" role="status">{notice}</p>}
    <label htmlFor="movement-sort">History order</label><select id="movement-sort" value={sort} onChange={event => { setSort(event.target.value); setPage(0) }}><option value="createdAt,desc">Newest first</option><option value="createdAt,asc">Oldest first</option></select>
    {history.isPending && <p role="status">Loading stock history…</p>}
    <Failure error={history.error} />{history.isError && <button className="secondary" onClick={() => void history.refetch()}>Retry history</button>}
    {history.data && !history.isError && <>{history.data.content.length ? <div className="table-scroll inventory-history" tabIndex={0} role="region" aria-label="Stock movement history table"><table><caption className="sr-only">Recorded product stock movements</caption><thead><tr><th scope="col">Date</th><th scope="col">Type</th><th scope="col" className="numeric">Change</th><th scope="col">Reason / reference</th><th scope="col">Actor</th></tr></thead><tbody>{history.data.content.map(movement => <tr key={movement.id}><td><time dateTime={movement.createdAt}>{new Date(movement.createdAt).toLocaleString()}</time></td><td>{typeNames[movement.type] ?? movement.type}{movement.orderId && <p className="muted">Order: <code>{movement.orderId}</code></p>}</td><td className="numeric"><strong>{movement.quantityDelta > 0 ? '+' : ''}{movement.quantityDelta}</strong></td><td><p>{movement.reason ?? 'No reason provided'}</p><p className="muted">Ref: {movement.reference ?? 'Not provided'}</p></td><td><code title={movement.createdBy}>{movement.createdBy}</code></td></tr>)}</tbody></table></div> : <p>No stock movements on this page. New products begin at zero stock.</p>}<Pager {...history.data} page={page} onChange={setPage} pending={history.isFetching} /></>}
    {dialog && session?.user.role === 'ADMIN' && <StockDialog key={dialog} product={product} type={dialog} close={() => setDialog(null)} recorded={movement => { setPage(0); setSort('createdAt,desc'); setDialog(null); setNotice(`${typeNames[movement.type]} recorded (${movement.quantityDelta > 0 ? '+' : ''}${movement.quantityDelta}). Balance and history are being refreshed.`) }} />}
  </section>
}
