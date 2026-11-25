export function BrandMark({ size = 40 }: { size?: number }) {
  return (
    <svg
      className="brand-mark"
      width={size}
      height={size}
      viewBox="0 0 64 64"
      fill="none"
      aria-hidden="true"
    >
      <path
        className="brand-mark__pages"
        d="M32 18c-5-3.5-12-4-18-3v31c6-1 13-.5 18 3 5-3.5 12-4 18-3V15c-6-1-13-.5-18 3Z"
        stroke="currentColor"
        strokeWidth="2.5"
        strokeLinejoin="round"
      />
      <path
        className="brand-mark__spine"
        d="M32 18v31"
        stroke="var(--brass)"
        strokeWidth="2.5"
        strokeLinecap="round"
      />
      <circle className="brand-mark__spark" cx="49" cy="10" r="2.2" fill="var(--brass-bright)" />
    </svg>
  )
}

/** Hero-scale wordmark for login / register / home. */
export function BrandHero({ tagline, compact = false }: { tagline?: string; compact?: boolean }) {
  return (
    <header className={`brand-hero${compact ? ' brand-hero--compact' : ''}`}>
      <BrandMark size={compact ? 44 : 60} />
      <h1 className="brand-hero__name">
        <span>Book</span> <em>Buddy</em>
      </h1>
      {tagline && <p className="brand-hero__tagline">{tagline}</p>}
    </header>
  )
}
