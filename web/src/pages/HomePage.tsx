import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { chatApi, type Conversation } from '../api/chat'
import { BrandHero } from '../components/BrandMark'
import { useAuth } from '../auth/useAuth'
import { strings } from '../i18n'

function greeting(): string {
  const h = new Date().getHours()
  const g = strings.home.greeting
  if (h < 5) return g.lateNight
  if (h < 12) return g.morning
  if (h < 18) return g.afternoon
  return g.evening
}

export function HomePage() {
  const { user } = useAuth()
  const name = user?.displayName?.split(' ')[0]
  const [recent, setRecent] = useState<Conversation[] | null>(null)
  const t = strings.home

  useEffect(() => {
    const ctrl = new AbortController()
    chatApi
      .list(4, null, ctrl.signal)
      .then((page) => setRecent(page.items))
      .catch(() => setRecent([]))
    return () => ctrl.abort()
  }, [])

  return (
    <div className="home">
      <section className="hero">
        <p className="eyebrow">
          {greeting()}
          {name ? `, ${name}` : ''}
        </p>
        <BrandHero tagline={t.tagline} />
        <div className="hero__actions">
          <Link to="/chat" className="btn btn--primary btn--lg">
            {t.talk}
          </Link>
          <Link to="/library" className="btn btn--ghost btn--lg">
            {t.browse}
          </Link>
        </div>
      </section>

      <section className="home__recent" aria-labelledby="home-recent">
        <header className="home__recent-head">
          <h2 id="home-recent">{t.recentTitle}</h2>
          <Link to="/chat" className="link-btn">
            {t.newChat}
          </Link>
        </header>
        {recent && recent.length > 0 ? (
          <ul className="recent-list">
            {recent.map((c) => (
              <li key={c.id}>
                <Link to={`/chat/${c.id}`} className="recent-list__item">
                  <span className="recent-list__title">{c.title}</span>
                  {c.incognito && <span className="conv__badge">{t.incognito}</span>}
                </Link>
              </li>
            ))}
          </ul>
        ) : (
          <p className="home__recent-empty" role={recent === null ? 'status' : undefined}>
            {recent === null ? strings.a11y.loading : t.recentEmpty}
          </p>
        )}
      </section>
    </div>
  )
}
