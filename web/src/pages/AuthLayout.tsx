import type { ReactNode } from 'react'
import { BrandHero } from '../components/BrandMark'
import { strings } from '../i18n'

export function AuthLayout({ children }: { children: ReactNode }) {
  const t = strings.auth
  return (
    <div className="auth">
      <div className="auth__hero">
        <BrandHero tagline={t.tagline} />
        <blockquote className="auth__quote">
          {t.quote}
          <cite>{t.quoteCite}</cite>
        </blockquote>
      </div>
      <main className="auth__panel" id="main-content">
        <div className="auth__card">{children}</div>
      </main>
    </div>
  )
}
