/**
 * English UI strings. Every string a person reads in a key page lives here so a Hindi catalogue (`hi.ts`, same
 * shape: `export const hi: Strings = {...}`) can be added later without touching components.
 *
 * Conventions: group by screen; use a function when a string needs a value (so word order can differ per language);
 * keep punctuation inside the string.
 */
export const en = {
  app: {
    name: 'Book Buddy',
    nameFirst: 'Book',
    nameSecond: 'Buddy',
    footnote: 'Your reading companion.',
  },

  a11y: {
    skipToContent: 'Skip to main content',
    primaryNav: 'Primary',
    homeLink: 'Book Buddy home',
    loading: 'Loading…',
  },

  nav: {
    home: 'Home',
    library: 'Library',
    chat: 'Chat',
    memory: 'Memory',
    settings: 'Settings',
  },

  time: {
    seconds: (n: number) => `${n} ${n === 1 ? 'second' : 'seconds'}`,
    minutes: (n: number) => `${n} ${n === 1 ? 'minute' : 'minutes'}`,
    hours: (n: number) => `${n} ${n === 1 ? 'hour' : 'hours'}`,
  },

  errors: {
    generic: 'Something went wrong.',
    network: "Can't reach Book Buddy right now. Check your connection and try again.",
    /** HTTP 429 / RATE_LIMITED without a wait hint. */
    rateLimited: 'Book Buddy is getting more requests than it can handle right now. Please wait a moment and try again.',
    rateLimitedWait: (wait: string) =>
      `Book Buddy is getting more requests than it can handle right now. Please try again in about ${wait}.`,
    /** QUOTA_EXHAUSTED: the free daily AI allowance is used up. */
    quotaExhausted:
      "Book Buddy has used up its free AI allowance for today. It resets every day, so please try again later. You can still browse your library and notes.",
    quotaExhaustedWait: (wait: string) =>
      `Book Buddy has used up its free AI allowance for today. It should be back in about ${wait}. You can still browse your library and notes.`,
    tryAgainIn: (message: string, wait: string) => `${message} Try again in ${wait}.`,
  },

  quota: {
    title: 'AI usage today',
    lede: "Book Buddy runs on a free daily AI allowance. This is a rough view of how much is left; it resets every day.",
    unavailable: "Couldn't check the AI allowance right now.",
    states: {
      OK: 'Plenty left',
      LOW: 'Running low',
      EXHAUSTED: 'Used up for today',
    },
    percentUsed: (n: number) => `${n}% used`,
    resetsIn: (wait: string) => `Resets in about ${wait}.`,
    explainOk: 'Everything is working as normal.',
    explainBackground: 'Background tidy-ups (like memory clean-up) are slowing down to save the allowance for chat.',
    explainLow: 'Replies still work, but background features are paused so chat can keep going.',
    explainExhausted: "Chat replies may fail or fall back to quotes from your books until the allowance resets.",
    chatNotice: {
      LOW: 'The free AI allowance is running low today, so replies may sometimes be slow or limited.',
      EXHAUSTED: 'The free AI allowance is used up for today. Replies may fail until it resets.',
    },
  },

  auth: {
    login: {
      title: 'Welcome back',
      subtitle: 'Sign in to pick up where you left off.',
      email: 'Email',
      emailPlaceholder: 'you@example.com',
      password: 'Password',
      passwordPlaceholder: '••••••••',
      submit: 'Sign in',
      submitting: 'Signing in…',
      newHere: 'New to Book Buddy?',
      createAccount: 'Create an account',
    },
  },

  home: {
    greeting: {
      lateNight: 'Still up',
      morning: 'Good morning',
      afternoon: 'Good afternoon',
      evening: 'Good evening',
    },
    tagline: "Your reading companion — ask about a chapter, a character, or a line you can't stop thinking about.",
    talk: 'Talk to Book Buddy',
    browse: 'Browse library',
    continueReading: 'Continue reading',
    soon: 'Soon',
    continueNote: 'Pick up your current book right where you paused. Reading progress is on its way.',
    recentTitle: 'Recent conversations',
    newChat: 'New chat',
    incognito: 'Incognito',
    recentEmpty: 'Your chats with Book Buddy will gather here, so you can return to any thread.',
  },

  memory: {
    eyebrow: 'What Book Buddy knows',
    title: 'Memory',
    lede:
      'Everything here is yours to read, correct or erase. Book Buddy uses these notes to make its answers and recommendations feel personal.',
    pausedTitle: 'Memory is paused',
    onTitle: 'Let Book Buddy remember me',
    pausedNote:
      'Nothing is being remembered or used in chats. Your saved notes below are kept until you remove them.',
    onNote: 'Pause this any time. Notes you have saved stay put but are not used while paused.',
    rememberToggle: 'Remember me',
    profileTitle: 'How Book Buddy sees you',
    profileEmpty: 'Nothing yet. As you chat, or add notes below, a short summary will appear here.',
    answerQuestions: 'Answer a few questions',
    addTitle: 'Add a memory',
    category: 'Category',
    somethingToRemember: 'Something to remember',
    addPlaceholder: 'e.g. I prefer paperbacks and short chapters',
    add: 'Add',
    adding: 'Adding…',
    loading: 'Loading your memories…',
    emptyTitle: 'No memories yet',
    emptyBody: 'Chat with Book Buddy, add a note above, or answer a few quick questions to get started.',
    clearTitle: 'Clear everything',
    clearAll: 'Clear all memories',
    clearing: 'Clearing…',
    pin: 'Pin',
    unpin: 'Unpin',
    edit: 'Edit',
    forget: 'Forget',
    save: 'Save',
    cancel: 'Cancel',
    memoryText: 'Memory text',
  },
};

export type Strings = typeof en;
