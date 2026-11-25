import { strings } from '../i18n'
import { BrandMark } from './BrandMark'

export function Splash() {
  return (
    <div className="splash" role="status" aria-live="polite">
      <div className="splash__mark">
        <BrandMark size={56} />
      </div>
      <p className="splash__message">{strings.splash.message}</p>
    </div>
  )
}
