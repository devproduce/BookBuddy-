import type { SafetyEvent } from '../../api/chat'

const telHref = (phone: string) => `tel:${phone.replace(/[^+\d]/g, '')}`

/** Helplines shown next to a reply when the safety check flagged distress. */
export function CrisisCard({ safety }: { safety: SafetyEvent }) {
  const acute = safety.level === 'ACUTE'
  return (
    <aside className={`crisis${acute ? ' crisis--acute' : ''}`} role={acute ? 'alert' : 'note'}>
      <h3 className="crisis__title">
        {acute ? "You're not alone. Help is available right now." : 'Support is here if you want it.'}
      </h3>
      <p className="crisis__text">
        {acute
          ? 'If you might act on these thoughts or you are in danger, please call your local emergency number or one of these free helplines.'
          : 'Talking to someone you trust, or to a helpline, can make a heavy moment lighter.'}
      </p>
      <ul className="crisis__list">
        {safety.resources.map((r) => (
          <li key={`${r.name}-${r.phone}`}>
            <span className="crisis__name">
              {r.name}
              {r.personal && <em> · your contact</em>}
            </span>
            <a className="btn btn--sm crisis__call" href={telHref(r.phone)}>
              Call {r.phone}
            </a>
          </li>
        ))}
      </ul>
    </aside>
  )
}