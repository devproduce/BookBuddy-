import { NavLink, Outlet, useLocation } from 'react-router-dom'
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
  { to: '/', label: 'Home', icon: 'home', end: true },
  { to: '/library', label: 'Library', icon: 'library' },
  { to: '/chat', label: 'Chat', icon: 'chat' },
  { to: '/memory', label: 'Memory', icon: 'memory', soon: true },
  { to: '/settings', label: 'Settings', icon: 'settings' },
] as const

export function AppShell() {
  const location = useLocation()

  return (
    <div className="shell">
      <aside className="shell__rail">
        <NavLink to="/" className="shell__logo" aria-label="Book Buddy home">
          <BrandMark size={30} />
          <span>
            Book <em>Buddy</em>
          </span>
        </NavLink>
        <nav className="nav" aria-label="Primary">
          {NAV.map((item) => {
            const soon = 'soon' in item && item.soon
            const content = (
              <>
                <Icon name={item.icon} />
                <span className="nav__label">{item.label}</span>
                {soon && <span className="nav__soon">Soon</span>}
              </>
            )
            return soon ? (
              <span key={item.to} className="nav__item nav__item--disabled" aria-disabled="true">
                {content}
              </span>
            ) : (
              <NavLink
                key={item.to}
                to={item.to}
                end={'end' in item ? item.end : false}
                className={({ isActive }) => `nav__item${isActive ? ' is-active' : ''}`}
              >
                {content}
              </NavLink>
            )
          })}
        </nav>
        <p className="shell__footnote">Your reading companion.</p>
      </aside>
      <main className="shell__main">
        {/* key re-mounts the wrapper so the enter animation replays per route */}
        <div className="page" key={location.pathname.startsWith('/chat') ? '/chat' : location.pathname}>
          <Outlet />
        </div>
      </main>
    </div>
  )
}
