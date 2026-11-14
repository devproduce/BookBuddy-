import type { ChatMessage, SafetyEvent } from '../../api/chat'

/** A server message plus transient client-side state while it streams. */
export interface UiMessage extends ChatMessage {
  /** Stable React key; survives the temporary-id -> real-id swap when the stream starts. */
  key: string
  streaming?: boolean
  /** Set from the live `safety` event (resources are not stored on the message). */
  safety?: SafetyEvent | null
  errorText?: string | null
}

export const toUi = (m: ChatMessage): UiMessage => ({ ...m, key: m.id })

export const isLocalId = (id: string) => id.startsWith('local-')

let counter = 0
export const uid = () => `k${Date.now().toString(36)}${(counter++).toString(36)}`