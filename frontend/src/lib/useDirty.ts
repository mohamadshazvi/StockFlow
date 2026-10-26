import { useEffect, useRef } from 'react'
import { useBlocker } from 'react-router-dom'
import { useAuth } from '../auth'

export function useDirty(dirty: boolean) {
  const { session } = useAuth()
  const bypass = useRef(false)
  const blocker = useBlocker(({ currentLocation, nextLocation }) => Boolean(session) && dirty && !bypass.current && (currentLocation.pathname !== nextLocation.pathname || currentLocation.search !== nextLocation.search))
  useEffect(() => {
    if (blocker.state !== 'blocked') return
    if (window.confirm('Discard your unsaved changes?')) blocker.proceed()
    else blocker.reset()
  }, [blocker])
  useEffect(() => {
    if (!dirty || !session) return
    const unload = (event: BeforeUnloadEvent) => { event.preventDefault(); event.returnValue = '' }
    const signout = (event: Event) => { if (!window.confirm('Sign out and discard your unsaved changes?')) event.preventDefault() }
    window.addEventListener('beforeunload', unload); window.addEventListener('stockflow:signout', signout)
    return () => { window.removeEventListener('beforeunload', unload); window.removeEventListener('stockflow:signout', signout) }
  }, [dirty, session])
  // Call before successful-save navigation; mutation success renders after its callback.
  return () => { bypass.current = true }
}
