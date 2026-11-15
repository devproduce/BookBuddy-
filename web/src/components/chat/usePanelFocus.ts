import { useEffect, useRef, type KeyboardEvent } from 'react'

/**
 * Keyboard behaviour for the side drawers (passage / "why this answer"):
 * - on open, focus moves into the drawer so keyboard and screen-reader users land on it;
 * - Escape closes it;
 * - on close, focus goes back to whatever opened it (if that element is still on the page).
 *
 * The drawers sit beside the conversation rather than over it, so they are non-modal and focus is not trapped.
 */
export function usePanelFocus<T extends HTMLElement>(onClose: () => void) {
  const ref = useRef<T>(null)
  useEffect(() => {
    const opener = document.activeElement instanceof HTMLElement ? document.activeElement : null
    ref.current?.focus({ preventScroll: true })
    return () => {
      if (opener && opener !== document.body && opener.isConnected) opener.focus({ preventScroll: true })
    }
  }, [])

  const onKeyDown = (e: KeyboardEvent<T>) => {
    if (e.key === 'Escape' && !e.defaultPrevented) {
      e.stopPropagation()
      onClose()
    }
  }

  return [ref, onKeyDown] as const
}
