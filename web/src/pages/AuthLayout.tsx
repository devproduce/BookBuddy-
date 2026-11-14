import type { ReactNode } from 'react'
import { BrandHero } from '../components/BrandMark'

export function AuthLayout({ children }: { children: ReactNode }) {
  return (
    <div className="auth">
      <div className="auth__hero">
        <BrandHero tagline="Read slowly. Talk it through. A companion for every book you open." />
        <blockquote className="auth__quote">
          “A reader lives a thousand lives before he dies.”
          <cite>George R. R. Martin</cite>
        </blockquote>
      </div>
      <div className="auth__panel">
        <div className="auth__card">{children}</div>
      </div>
    </div>
  )
}
