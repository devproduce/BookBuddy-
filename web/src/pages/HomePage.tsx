import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { chatApi, type Conversation } from '../api/chat'
import { BrandHero } from '../components/BrandMark'
import { useAuth } from '../auth/useAuth'

function greeting(): string {
  const h = new Date().getHours()
  if (h < 5) return 'Still up'
  if (h < 12) return 'Good morning'
  if (h < 18) return 'Good afternoon'
  return 'Good evening'
}

export function HomePage() {
  const { user } = useAuth()
  const name = user?.displayName?.split(' ')[0]
  const [recent, setRecent] = useState<Conversation[] | null>(null)

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
        <BrandHero tagline="Your reading companion — ask about a chapter, a character, or a line you can't stop thinking about." />
        <div className="hero__actions">
          <Link to="/chat" className="btn btn--primary btn--lg">
            Talk to Book Buddy
          </Link>
          <Link to="/library" className="btn btn--ghost btn--lg">
            Browse library
          </Link>
        </div>
      </section>

      <div className="home__grid">
        <section className="panel">
          <header className="panel__head">
            <h2>Continue reading</h2>
            <span className="tag">Soon</span>
          </header>
          <div className="placeholder placeholder--book">
            <div className="placeholder__cover" aria-hidden="true" />
            <div className="placeholder__lines">
              <span style={{ width: '70%' }} />
              <span style={{ width: '45%' }} />
              <span style={{ width: '60%' }} />
            </div>
          </div>
          <p className="panel__note">
            Pick up your current book right where you paused. Reading progress is on its way.
          </p>
        </section>

        <section className="panel">
          <header className="panel__head">
            <h2>Recent conversations</h2>
            <Link to="/chat" className="link-btn">
              New chat
            </Link>
          </header>
          {recent && recent.length > 0 ? (
            <ul className="recent-list">
              {recent.map((c) => (
                <li key={c.id}>
                  <Link to={`/chat/${c.id}`} className="recent-list__item">
                    <span className="recent-list__title">{c.title}</span>
                    {c.incognito && <span className="conv__badge">Incognito</span>}
                  </Link>
                </li>
              ))}
            </ul>
          ) : (
            <p className="panel__note">
              {recent === null
                ? 'Loading…'
                : 'Your chats with Book Buddy will gather here, so you can return to any thread.'}
            </p>
          )}
        </section>
      </div>
    </div>
  )
}
