import { useEffect, useRef } from 'react'
import { NavLink, Outlet, useLocation } from 'react-router-dom'
import { strings } from '../i18n'
import { BrandMark } from './BrandMark'

function Icon({ name }: { name: 'home' | 'library' | 'chat' | 'memory' | 'settings' }) {
  const common = {
    width: 22,
    height: 22,
    viewBox: '0 0 24 24',
    fill: 'none',
    stroke: 'currentColor',
    strokeWidth: 1.6,
    strokeLinecap: 'round' as const,
    strokeLinejoin: 'round' as const,
    'aria-hidden': true,
  }
  switch (name) {
    case 'home':
      return (
        <svg {...common}>
          <path d="M4 11 12 4l8 7v8a1 1 0 0 1-1 1h-4v-6H9v6H5a1 1 0 0 1-1-1v-8Z" />
        </svg>
      )
    case 'library':
      return (
        <svg {...common}>
          <path d="M5 4v16M10 4v16M15 5l4 15" />
        </svg>
      )
    case 'chat':
      return (
        <svg {...common}>
          <path d="M5 6h14a1 1 0 0 1 1 1v8a1 1 0 0 1-1 1h-8l-4 3v-3H5a1 1 0 0 1-1-1V7a1 1 0 0 1 1-1Z" />
        </svg>
      )
    case 'memory':
      return (
        <svg {...common}>
          <path d="M12 3c-4 0-7 2.7-7 6.2 0 2 1 3.5 2.4 4.6.4.4.6.9.6 1.4V17h8v-1.8c0-.5.2-1 .6-1.4C18 12.7 19 11.2 19 9.2 19 5.7 16 3 12 3ZM9.5 20h5" />
        </svg>
      )
    case 'settings':
      return (
        <svg {...common}>
          <path d="M4 7h10M18 7h2M4 17h2M10 17h10" />
          <circle cx="16" cy="7" r="2" />
          <circle cx="8" cy="17" r="2" />
        </svg>
      )
  }
}

const NAV = [
  { to: '/', label: strings.nav.home, icon: 'home', end: true },
  { to: '/library', label: strings.nav.library, icon: 'library' },
  { to: '/chat', label: strings.nav.chat, icon: 'chat' },
  { to: '/memory', label: strings.nav.memory, icon: 'memory' },
  { to: '/settings', label: strings.nav.settings, icon: 'settings' },
] as const

export function AppShell() {
  const location = useLocation()
  const mainRef = useRef<HTMLElement>(null)

  // Client-side navigation does not move focus on its own, so keyboard and screen-reader
  // users would be left on the link they just activated. Move focus to <main> when the
  // top-level section changes (not for in-section moves like switching conversations, and
  // not on the first render, where the browser's own starting point is right).
  const section = location.pathname.split('/')[1] ?? ''
  const previousSection = useRef(section)
  useEffect(() => {
    if (previousSection.current !== section) {
      previousSection.current = section
      mainRef.current?.focus({ preventScroll: true })
    }
  }, [section])

  return (
    <div className="shell">
      <a className="skip-link" href="#main-content">
        {strings.a11y.skipToContent}
      </a>
      <aside className="shell__rail">
        <NavLink to="/" className="shell__logo" aria-label={strings.a11y.homeLink}>
          <BrandMark size={30} />
          <span>
            Book <em>Buddy</em>
          </span>
        </NavLink>
        <nav className="nav" aria-label={strings.a11y.primaryNav}>
          {NAV.map((item) => {
            return (
              <NavLink
                key={item.to}
                to={item.to}
                end={'end' in item ? item.end : false}
                className={({ isActive }) => `nav__item${isActive ? ' is-active' : ''}`}
              >
                <Icon name={item.icon} />
                <span className="nav__label">{item.label}</span>
              </NavLink>
            )
          })}
        </nav>
        <p className="shell__footnote">{strings.app.footnote}</p>
      </aside>
      <main className="shell__main" id="main-content" tabIndex={-1} ref={mainRef}>
        {/* key re-mounts the wrapper so the enter animation replays per route */}
        <div className="page" key={location.pathname.startsWith('/chat') ? '/chat' : location.pathname}>
          <Outlet />
        </div>
      </main>
    </div>
  )
}
