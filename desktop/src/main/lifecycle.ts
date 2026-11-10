let quitting = false

export function markQuitting(): void {
  quitting = true
}

export function isQuitting(): boolean {
  return quitting
}

export function launchedHidden(): boolean {
  return process.argv.includes('--hidden')
}
