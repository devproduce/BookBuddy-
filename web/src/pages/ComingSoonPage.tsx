import { Link } from 'react-router-dom'

export function ComingSoonPage({ title, blurb }: { title: string; blurb: string }) {
  return (
    <div className="empty">
      <p className="eyebrow">Coming soon</p>
      <h2>{title}</h2>
      <p>{blurb}</p>
      <Link to="/library" className="btn btn--ghost">
        Back to library
      </Link>
    </div>
  )
}

export function NotFoundPage() {
  return (
    <div className="empty">
      <p className="eyebrow">Lost page</p>
      <h2>Between the covers</h2>
      <p>This page seems to have slipped out of the shelf. Let’s find your way back.</p>
      <Link to="/" className="btn btn--primary">
        Go home
      </Link>
    </div>
  )
}
