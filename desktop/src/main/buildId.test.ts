import { expect, test } from 'vitest'
import { composeBuildId, decideSecondInstance, filterRelaunchArgs } from './buildId'

const running = ['--hidden', '/tmp/.mount_old/temps-ecran-desktop', '/home/user/old.AppImage']

test('build id changes when the stamp changes and the version does not', () => {
  const first = composeBuildId('1.0.0', '100')
  const second = composeBuildId('1.0.0', '200')
  expect(first).not.toBe(second)
  expect(composeBuildId('1.0.0', '100')).toBe(composeBuildId('1.0.0', '100'))
  expect(composeBuildId('1.0.1', '100')).not.toBe(first)
})

test('a different build hands over to the new executable without the old arguments', () => {
  const decision = decideSecondInstance(
    composeBuildId('1.0.0', '100'),
    {
      buildId: composeBuildId('1.0.0', '200'),
      execPath: '/home/user/Temps-ecran.AppImage',
      argv: ['--hidden', '--fresh', '/tmp/.mount_old/temps-ecran-desktop', '']
    },
    running
  )
  expect(decision).toEqual({
    action: 'replace',
    execPath: '/home/user/Temps-ecran.AppImage',
    args: ['--fresh']
  })
})

test('the same build only asks to show the window', () => {
  const buildId = composeBuildId('1.0.0', '100')
  expect(
    decideSecondInstance(
      buildId,
      { buildId, execPath: '/home/user/Temps-ecran.AppImage', argv: ['--hidden'], extra: true },
      running
    )
  ).toEqual({ action: 'show' })
})

test('missing additionalData from an older instance is ignored', () => {
  const buildId = composeBuildId('1.0.0', '100')
  const payloads: unknown[] = [
    undefined,
    null,
    {},
    [],
    '1.0.0+100',
    { buildId },
    { buildId, execPath: '' },
    { buildId: '', execPath: '/new', argv: [] },
    { buildId: 'other', execPath: '/new', argv: ['ok', 1] },
    { execPath: '/new', argv: [] }
  ]
  for (const payload of payloads) {
    expect(decideSecondInstance(buildId, payload, running)).toEqual({ action: 'ignore' })
  }
})

test('relaunch args keep order and only drop exact arguments from the running process', () => {
  expect(filterRelaunchArgs(['--hidden', '--fresh', '--hidden', '--hidden-other'], ['--hidden'])).toEqual([
    '--fresh',
    '--hidden-other'
  ])
  expect(filterRelaunchArgs([], running)).toEqual([])
})
