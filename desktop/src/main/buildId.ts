export type InstanceLaunch = {
  buildId: string
  execPath: string
  argv: string[]
}

export type SecondInstanceDecision =
  | { action: 'ignore' }
  | { action: 'show' }
  | { action: 'replace'; execPath: string; args: string[] }

/** Version plus a per-build stamp. The version alone stays put across replaced AppImages. */
export function composeBuildId(version: string, stamp: string): string {
  return `${version}+${stamp}`
}

function isStringArray(value: unknown): value is string[] {
  return Array.isArray(value) && value.every((item) => typeof item === 'string')
}

/** Old builds send no additionalData. A partial payload is the same: nothing to hand over to. */
export function readInstanceLaunch(value: unknown): InstanceLaunch | null {
  if (value == null || typeof value !== 'object') return null
  const record = value as { buildId?: unknown; execPath?: unknown; argv?: unknown }
  if (typeof record.buildId !== 'string' || record.buildId.length === 0) return null
  if (typeof record.execPath !== 'string' || record.execPath.length === 0) return null
  if (!isStringArray(record.argv)) return null
  return { buildId: record.buildId, execPath: record.execPath, argv: record.argv }
}

/** Drop arguments the process we are replacing already carries, including its own binary path. */
export function filterRelaunchArgs(incoming: readonly string[], runningArgs: readonly string[]): string[] {
  const stale = new Set(runningArgs)
  return incoming.filter((arg) => arg.length > 0 && !stale.has(arg))
}

export function decideSecondInstance(
  ourBuildId: string,
  additionalData: unknown,
  runningArgs: readonly string[]
): SecondInstanceDecision {
  const incoming = readInstanceLaunch(additionalData)
  if (!incoming) return { action: 'ignore' }
  if (incoming.buildId === ourBuildId) return { action: 'show' }
  return {
    action: 'replace',
    execPath: incoming.execPath,
    args: filterRelaunchArgs(incoming.argv, runningArgs)
  }
}
