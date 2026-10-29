import { afterEach } from 'vitest'
import { cleanup } from '@testing-library/react'

afterEach(cleanup)

// jsdom has neither of these; Recharts and <dialog> need them.
globalThis.ResizeObserver ??= class {
  observe() {}
  unobserve() {}
  disconnect() {}
}
HTMLDialogElement.prototype.showModal ??= function showModal() {
  this.open = true
}
HTMLDialogElement.prototype.close ??= function close() {
  this.open = false
}
