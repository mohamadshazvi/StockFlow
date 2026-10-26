import { useEffect, useRef, type ReactNode } from 'react'
import { ApiError } from '../lib/api'

export function Failure({ error }: { error: Error | null }) {
  const summary = useRef<HTMLDivElement>(null)
  useEffect(() => { if (error) summary.current?.focus() }, [error])
  if (!error) return null
  return <div ref={summary} className="error" role="alert" tabIndex={-1}><strong>{error instanceof ApiError ? error.message : 'Could not confirm the result. Refresh before retrying.'}</strong>{error instanceof ApiError && error.fieldErrors.length > 0 && <ul>{error.fieldErrors.map((field, i) => <li key={i}>{field.field}: {field.message}</li>)}</ul>}</div>
}

export function Pager({ page, totalPages, totalElements, onChange, pending = false }: { page: number; totalPages: number; totalElements: number; onChange: (value: number) => void; pending?: boolean }) {
  return <div className="pagination"><p className="muted" role="status">{totalElements} records · Page {totalPages ? page + 1 : 0} of {totalPages}</p><div><button type="button" className="secondary" disabled={!page || pending} onClick={() => onChange(page - 1)}>Previous</button><button type="button" className="secondary" disabled={page + 1 >= totalPages || pending} onClick={() => onChange(page + 1)}>Next</button></div></div>
}

export function Modal({ title, children, close, pending = false }: { title: string; children: ReactNode; close: () => void; pending?: boolean }) {
  const dialog = useRef<HTMLDialogElement>(null)
  useEffect(() => {
    const previous = document.activeElement, element = dialog.current
    element?.showModal()
    return () => { element?.close(); if (previous instanceof HTMLElement && previous.isConnected) previous.focus() }
  }, [])
  return <dialog ref={dialog} aria-labelledby="dialog-title" onKeyDown={event => {
    if (event.key !== 'Tab') return
    const controls = [...event.currentTarget.querySelectorAll<HTMLElement>('button:not(:disabled), a[href], input:not(:disabled), select:not(:disabled), textarea:not(:disabled), [tabindex="0"]')].filter(element => element.getClientRects().length > 0)
    const first = controls[0], last = controls[controls.length - 1]
    if (!first) { event.preventDefault(); event.currentTarget.focus(); return }
    if (event.shiftKey && document.activeElement === first) { event.preventDefault(); last.focus() }
    else if (!event.shiftKey && document.activeElement === last) { event.preventDefault(); first.focus() }
  }} onCancel={event => { event.preventDefault(); if (!pending) close() }}><div className="panel-heading"><h2 id="dialog-title">{title}</h2><button type="button" className="secondary" onClick={close} disabled={pending} aria-label="Close dialog">×</button></div>{children}</dialog>
}

export function StatusBadge({ status }: { status: string }) {
  return <span className={`badge ${status === 'CONFIRMED' || status === 'Active' ? 'ready' : status === 'CANCELLED' || status === 'Archived' ? 'neutral' : ''}`}>{status}</span>
}
