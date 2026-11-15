import { en, type Strings } from './en'

export type { Strings }

/**
 * The active UI strings. Only English exists today. To add Hindi later: create `hi.ts` exporting
 * `hi: Strings` (the compiler flags every missing key), then pick the catalogue here from the user's language
 * setting and set `document.documentElement.lang` to match.
 */
export const strings: Strings = en
