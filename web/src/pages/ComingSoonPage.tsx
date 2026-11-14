import { Link } from 'react-router-dom'

export function ComingSoonPage({ title, blurb }: { title: string; blurb: string }) {
  return (
    <div className="empty">
      <span className="tag">Coming soon</span>
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
      <h2>Page not found</h2>
      <p>This page seems to have slipped between the covers.</p>
      <Link to="/" className="btn btn--primary">
        Go home
      </Link>
    </div>
  )
}
