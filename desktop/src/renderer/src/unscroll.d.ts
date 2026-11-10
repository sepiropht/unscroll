import type { UnscrollApi } from '@shared/types'

declare global {
  interface Window {
    unscroll: UnscrollApi
  }
}

export {}
