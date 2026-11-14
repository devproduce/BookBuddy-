export function Alert({ children }: { children: React.ReactNode }) {
  if (!children) return null
  return (
    <div className="alert" role="alert">
      {children}
    </div>
  )
}
