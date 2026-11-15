import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react'
import {
  MEMORY_CATEGORIES,
  memoryApi,
  type Memory,
  type MemoryCategory,
  type MemoryProfile,
} from '../api/memory'
import { settingsApi } from '../api/settings'
import { Alert } from '../components/Alert'
import { errorMessage } from '../components/errorMessage'

const MAX_LEN = 500

const ONBOARDING: { category: MemoryCategory; question: string; placeholder: string; prefix?: string }[] = [
  {
    category: 'INTERESTS',
    question: 'What kinds of books do you love most?',
    placeholder: 'e.g. slow-burn mysteries, narrative history, cosy fantasy',
    prefix: 'Loves ',
  },
  {
    category: 'INTERESTS',
    question: 'Any favourite authors or books you keep returning to?',
    placeholder: 'e.g. Ursula K. Le Guin, Pride and Prejudice',
    prefix: 'Favourites: ',
  },
  {
    category: 'PREFERENCES',
    question: 'Is there anything you tend to avoid or dislike in a book?',
    placeholder: 'e.g. graphic violence, very long series',
    prefix: 'Prefers to avoid: ',
  },
  {
    category: 'PREFERENCES',
    question: 'How do you like to read, and how should I talk to you about books?',
    placeholder: 'e.g. short evening sessions; keep answers brief and spoiler-free',
  },
  {
    category: 'GOALS',
    question: 'What are you hoping to get from your reading right now?',
    placeholder: 'e.g. read 20 books this year, learn about economics, unwind',
    prefix: 'Reading goal: ',
  },
  {
    category: 'ABOUT_ME',
    question: 'Anything about you that helps me recommend better?',
    placeholder: 'e.g. I commute an hour each way; English is my second language',
  },
  {
    category: 'EXPERIENCES',
    question: 'What is the best book you have read recently, and why?',
    placeholder: 'e.g. Piranesi — loved the strange, quiet atmosphere',
    prefix: 'Recently enjoyed: ',
  },
]

function sourceTag(m: Memory) {
  if (m.userVerified) return { text: 'Confirmed', warn: false }
  if (m.classification === 'EXPLICIT') return { text: 'You said', warn: false }
  return { text: 'Guess', warn: true }
}

export function MemoryPage() {
  const [memories, setMemories] = useState<Memory[] | null>(null)
  const [profile, setProfile] = useState<MemoryProfile | null>(null)
  const [enabled, setEnabled] = useState<boolean | null>(null)
  const [error, setError] = useState<string | null>(null)
  const [notice, setNotice] = useState<string | null>(null)
  const [busyId, setBusyId] = useState<string | null>(null)
  const [pausing, setPausing] = useState(false)

  const [editingId, setEditingId] = useState<string | null>(null)
  const [editText, setEditText] = useState('')
  const [editCategory, setEditCategory] = useState<MemoryCategory>('ABOUT_ME')

  const [addCategory, setAddCategory] = useState<MemoryCategory>('INTERESTS')
  const [addText, setAddText] = useState('')
  const [adding, setAdding] = useState(false)

  const [onboarding, setOnboarding] = useState(false)
  const [step, setStep] = useState(0)
  const [answers, setAnswers] = useState<string[]>(() => ONBOARDING.map(() => ''))
  const [savingOnboarding, setSavingOnboarding] = useState(false)

  const refreshProfile = useCallback(async () => {
    try {
      setProfile(await memoryApi.profile())
    } catch {
      /* the profile snippet is a nicety; the list is what matters */
    }
  }, [])

  useEffect(() => {
    const ctrl = new AbortController()
    memoryApi
      .list({ status: 'ACTIVE' }, ctrl.signal)
      .then((list) => {
        if (!ctrl.signal.aborted) setMemories(list)
      })
      .catch((err) => {
        if (!ctrl.signal.aborted) {
          setMemories([])
          setError(errorMessage(err))
        }
      })
    memoryApi
      .profile(ctrl.signal)
      .then((p) => {
        if (!ctrl.signal.aborted) setProfile(p)
      })
      .catch(() => undefined)
    settingsApi
      .get(ctrl.signal)
      .then((s) => {
        if (!ctrl.signal.aborted) setEnabled(s.memoryEnabled)
      })
      .catch(() => undefined)
    return () => ctrl.abort()
  }, [])

  const grouped = useMemo(() => {
    const map = new Map<MemoryCategory, Memory[]>()
    for (const m of memories ?? []) {
      const arr = map.get(m.category) ?? []
      arr.push(m)
      map.set(m.category, arr)
    }
    for (const arr of map.values()) {
      arr.sort((a, b) => Number(b.pinned) - Number(a.pinned) || b.updatedAt.localeCompare(a.updatedAt))
    }
    return map
  }, [memories])

  const total = memories?.length ?? 0

  function replace(updated: Memory) {
    setMemories((ms) => (ms ? ms.map((m) => (m.id === updated.id ? updated : m)) : ms))
  }

  async function run(id: string, fn: () => Promise<void>) {
    setBusyId(id)
    setError(null)
    setNotice(null)
    try {
      await fn()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  const togglePin = (m: Memory) =>
    run(m.id, async () => {
      replace(await memoryApi.update(m.id, { pinned: !m.pinned }))
      void refreshProfile()
    })

  const confirmMemory = (m: Memory) =>
    run(m.id, async () => {
      replace(await memoryApi.confirm(m.id))
      void refreshProfile()
    })

  const remove = (m: Memory) => {
    if (!window.confirm('Forget this memory? Book Buddy will stop using it.')) return Promise.resolve()
    return run(m.id, async () => {
      await memoryApi.remove(m.id)
      setMemories((ms) => (ms ? ms.filter((x) => x.id !== m.id) : ms))
      void refreshProfile()
    })
  }

  function startEdit(m: Memory) {
    setEditingId(m.id)
    setEditText(m.content)
    setEditCategory(m.category)
  }

  async function saveEdit(e: FormEvent, m: Memory) {
    e.preventDefault()
    const content = editText.trim()
    if (!content) return
    await run(m.id, async () => {
      const patch: { content?: string; category?: MemoryCategory } = {}
      if (content !== m.content) patch.content = content
      if (editCategory !== m.category) patch.category = editCategory
      if (Object.keys(patch).length > 0) {
        replace(await memoryApi.update(m.id, patch))
        void refreshProfile()
      }
      setEditingId(null)
    })
  }

  async function onAdd(e: FormEvent) {
    e.preventDefault()
    const content = addText.trim()
    if (!content) return
    setAdding(true)
    setError(null)
    setNotice(null)
    try {
      const created = await memoryApi.create({ category: addCategory, content })
      setMemories((ms) => [created, ...(ms ?? [])])
      setAddText('')
      setNotice('Memory added.')
      void refreshProfile()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setAdding(false)
    }
  }

  async function onClear() {
    if (total === 0) return
    if (
      !window.confirm(
        `Clear all ${total} ${total === 1 ? 'memory' : 'memories'}? Book Buddy will forget everything it knows about you. This can't be undone.`,
      )
    )
      return
    setBusyId('*')
    setError(null)
    setNotice(null)
    try {
      await memoryApi.clear()
      setMemories([])
      setNotice('All memories cleared.')
      void refreshProfile()
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setBusyId(null)
    }
  }

  async function onTogglePause(next: boolean) {
    setPausing(true)
    setError(null)
    setNotice(null)
    try {
      const s = await settingsApi.update({ memoryEnabled: next })
      setEnabled(s.memoryEnabled)
      setNotice(s.memoryEnabled ? 'Memory is on.' : 'Memory is paused. Nothing new will be remembered or used.')
    } catch (err) {
      setError(errorMessage(err))
    } finally {
      setPausing(false)
    }
  }

  // ---- onboarding ---------------------------------------------------------------------------------------
  function openOnboarding() {
    setAnswers(ONBOARDING.map(() => ''))
    setStep(0)
    setOnboarding(true)
  }

  async function finishOnboarding() {
    const toSave = ONBOARDING.map((q, i) => ({ q, text: answers[i].trim() })).filter((x) => x.text)
    if (toSave.length === 0) {
      setOnboarding(false)
      return
    }
    setSavingOnboarding(true)
    setError(null)
    setNotice(null)
    try {
      const created: Memory[] = []
      for (const { q, text } of toSave) {
        const content = `${q.prefix ?? ''}${text}`.slice(0, MAX_LEN)
        created.push(await memoryApi.create({ category: q.category, content }))
      }
      setMemories((ms) => [...created.reverse(), ...(ms ?? [])])
      setOnboarding(false)
      setNotice(`Saved ${created.length} ${created.length === 1 ? 'memory' : 'memories'}. Thank you!`)
      void refreshProfile()
    } catch (err) {
      setError(errorMessage(err))
      // keep the dialog open so nothing typed is lost
      const refreshed = await memoryApi.list({ status: 'ACTIVE' }).catch(() => null)
      if (refreshed) setMemories(refreshed)
    } finally {
      setSavingOnboarding(false)
    }
  }

  const q = ONBOARDING[step]
  const lastStep = step === ONBOARDING.length - 1

  return (
    <div className="memory">
      <header className="page-head page-head--stack">
        <p className="eyebrow">What Book Buddy knows</p>
        <h1 className="page-title">Memory</h1>
        <p className="lede">
          Everything here is yours to read, correct or erase. Book Buddy uses these notes to make its answers and
          recommendations feel personal.
        </p>
      </header>

      <Alert>{error}</Alert>
      {notice && (
        <p className="notice" role="status">
          {notice}
        </p>
      )}

      <section className="panel">
        <div className="toggle-row toggle-row--live">
          <div>
            <strong>{enabled === false ? 'Memory is paused' : 'Let Book Buddy remember me'}</strong>
            <p className="panel__note">
              {enabled === false
                ? 'Nothing is being remembered or used in chats. Your saved notes below are kept until you remove them.'
                : 'Pause this any time. Notes you have saved stay put but are not used while paused.'}
            </p>
          </div>
          <label className="switch switch--live" aria-label="Remember me">
            <input
              type="checkbox"
              checked={enabled ?? false}
              disabled={enabled === null || pausing}
              onChange={(e) => void onTogglePause(e.target.checked)}
            />
            <span className="switch__track" />
          </label>
        </div>
      </section>

      <section className="panel memory__profile">
        <header className="panel__head">
          <h2>How Book Buddy sees you</h2>
          {profile && (
            <span className="tag" title="Rough size of this summary in the prompt">
              ~{profile.estimatedTokens} / {profile.tokenBudget} tokens
            </span>
          )}
        </header>
        {profile === null ? (
          <p className="hint">Loading…</p>
        ) : profile.text.trim() ? (
          <blockquote className="memory__snippet">{profile.text}</blockquote>
        ) : (
          <p className="hint">Nothing yet. As you chat, or add notes below, a short summary will appear here.</p>
        )}
        <div className="panel__actions">
          <button type="button" className="btn btn--sm btn--ghost" onClick={openOnboarding}>
            Answer a few questions
          </button>
        </div>
      </section>

      {onboarding && (
        <section className="panel memory__onboard" aria-label="Quick questions">
          <header className="panel__head">
            <h2>
              Question {step + 1} <span className="memory__of">of {ONBOARDING.length}</span>
            </h2>
            <button
              type="button"
              className="link-btn"
              disabled={savingOnboarding}
              onClick={() => setOnboarding(false)}
            >
              Skip for now
            </button>
          </header>
          <div className="progress" aria-hidden="true">
            <div className="progress__bar" style={{ width: `${((step + 1) / ONBOARDING.length) * 100}%` }} />
          </div>
          <label className="field">
            <span className="field__label">{q.question}</span>
            <textarea
              className="memory__textarea"
              rows={3}
              maxLength={MAX_LEN - 40}
              value={answers[step]}
              placeholder={q.placeholder}
              autoFocus
              onChange={(e) => setAnswers((a) => a.map((v, i) => (i === step ? e.target.value : v)))}
            />
          </label>
          <p className="hint">Leave it blank to skip this one. Only what you write is saved.</p>
          <div className="panel__actions memory__onboard-actions">
            <button
              type="button"
              className="btn btn--sm btn--ghost"
              disabled={step === 0 || savingOnboarding}
              onClick={() => setStep((s) => s - 1)}
            >
              Back
            </button>
            {lastStep ? (
              <button
                type="button"
                className="btn btn--sm btn--primary"
                disabled={savingOnboarding}
                onClick={() => void finishOnboarding()}
              >
                {savingOnboarding ? 'Saving…' : 'Save my answers'}
              </button>
            ) : (
              <button
                type="button"
                className="btn btn--sm btn--primary"
                disabled={savingOnboarding}
                onClick={() => setStep((s) => s + 1)}
              >
                Next
              </button>
            )}
          </div>
        </section>
      )}

      <section className="panel">
        <header className="panel__head">
          <h2>Add a memory</h2>
        </header>
        <form className="memory__add" onSubmit={onAdd}>
          <label className="field">
            <span className="field__label">Category</span>
            <select value={addCategory} onChange={(e) => setAddCategory(e.target.value as MemoryCategory)}>
              {MEMORY_CATEGORIES.filter((c) => c.id !== 'OBSERVED_PATTERNS').map((c) => (
                <option key={c.id} value={c.id}>
                  {c.label}
                </option>
              ))}
            </select>
          </label>
          <label className="field memory__add-text">
            <span className="field__label">
              Something to remember<span className="field__opt">{addText.length}/{MAX_LEN}</span>
            </span>
            <input
              type="text"
              value={addText}
              maxLength={MAX_LEN}
              placeholder="e.g. I prefer paperbacks and short chapters"
              onChange={(e) => setAddText(e.target.value)}
            />
          </label>
          <button type="submit" className="btn btn--primary" disabled={adding || !addText.trim()}>
            {adding ? 'Adding…' : 'Add'}
          </button>
        </form>
      </section>

      <div className="memory__groups">
        {memories === null ? (
          <p className="hint">Loading your memories…</p>
        ) : total === 0 ? (
          <div className="empty">
            <h2>No memories yet</h2>
            <p>Chat with Book Buddy, add a note above, or answer a few quick questions to get started.</p>
          </div>
        ) : (
          MEMORY_CATEGORIES.map((cat) => {
            const items = grouped.get(cat.id)
            if (!items?.length) return null
            return (
              <section key={cat.id} className="panel memory__group">
                <header className="panel__head memory__group-head">
                  <div>
                    <h2>{cat.label}</h2>
                    <p className="panel__note memory__hint">{cat.hint}</p>
                  </div>
                  <span className="tag">{items.length}</span>
                </header>
                <ul className="memory__list">
                  {items.map((m) => {
                    const tag = sourceTag(m)
                    const needsConfirm = m.classification === 'INFERRED' && !m.userVerified
                    const busy = busyId === m.id || busyId === '*'
                    return (
                      <li key={m.id} className={`memory__item${m.pinned ? ' is-pinned' : ''}`}>
                        {editingId === m.id ? (
                          <form className="memory__edit" onSubmit={(e) => void saveEdit(e, m)}>
                            <select
                              aria-label="Category"
                              value={editCategory}
                              onChange={(e) => setEditCategory(e.target.value as MemoryCategory)}
                            >
                              {MEMORY_CATEGORIES.map((c) => (
                                <option key={c.id} value={c.id}>
                                  {c.label}
                                </option>
                              ))}
                            </select>
                            <textarea
                              className="memory__textarea"
                              rows={3}
                              maxLength={MAX_LEN}
                              value={editText}
                              aria-label="Memory text"
                              autoFocus
                              onChange={(e) => setEditText(e.target.value)}
                            />
                            <div className="panel__actions">
                              <button
                                type="submit"
                                className="btn btn--sm btn--primary"
                                disabled={busy || !editText.trim()}
                              >
                                Save
                              </button>
                              <button
                                type="button"
                                className="btn btn--sm btn--ghost"
                                disabled={busy}
                                onClick={() => setEditingId(null)}
                              >
                                Cancel
                              </button>
                            </div>
                          </form>
                        ) : (
                          <>
                            <p className="memory__text">
                              {m.pinned && (
                                <span className="memory__pin" title="Pinned" aria-label="Pinned">
                                  ★
                                </span>
                              )}
                              {m.content}
                            </p>
                            <p className="memory__meta">
                              <span className={`tag${tag.warn ? ' tag--warn' : ''}`}>{tag.text}</span>
                              {m.sensitivity === 'SENSITIVE' && <span className="tag tag--warn">Sensitive</span>}
                              <span className="memory__date">
                                {m.lastUsedAt
                                  ? `Used ${new Date(m.lastUsedAt).toLocaleDateString()}`
                                  : `Added ${new Date(m.createdAt).toLocaleDateString()}`}
                              </span>
                            </p>
                            {needsConfirm && (
                              <p className="memory__ask">
                                Book Buddy guessed this. Is it right?
                                <button
                                  type="button"
                                  className="btn btn--sm btn--primary"
                                  disabled={busy}
                                  onClick={() => void confirmMemory(m)}
                                >
                                  Yes, that's right
                                </button>
                              </p>
                            )}
                            <div className="memory__actions">
                              <button
                                type="button"
                                className="link-btn"
                                disabled={busy}
                                aria-pressed={m.pinned}
                                onClick={() => void togglePin(m)}
                              >
                                {m.pinned ? 'Unpin' : 'Pin'}
                              </button>
                              <button type="button" className="link-btn" disabled={busy} onClick={() => startEdit(m)}>
                                Edit
                              </button>
                              <button
                                type="button"
                                className="link-btn memory__forget"
                                disabled={busy}
                                onClick={() => void remove(m)}
                              >
                                Forget
                              </button>
                            </div>
                          </>
                        )}
                      </li>
                    )
                  })}
                </ul>
              </section>
            )
          })
        )}
      </div>

      {total > 0 && (
        <section className="panel memory__danger">
          <header className="panel__head">
            <h2>Clear everything</h2>
          </header>
          <p className="panel__note">
            Remove all {total} {total === 1 ? 'memory' : 'memories'}. Your books and conversations are not affected.
          </p>
          <div className="panel__actions">
            <button type="button" className="btn btn--sm btn--danger" disabled={busyId === '*'} onClick={() => void onClear()}>
              {busyId === '*' ? 'Clearing…' : 'Clear all memories'}
            </button>
          </div>
        </section>
      )}
    </div>
  )
}
