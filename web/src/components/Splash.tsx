import { BrandMark } from './BrandMark'

export function Splash() {
  return (
    <div className="splash" role="status" aria-live="polite">
      <BrandMark size={56} />
      <p>Opening your library…</p>
    </div>
  )
}
