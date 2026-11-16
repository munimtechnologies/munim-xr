// Releases the munim-xr package (packages/munim-xr) from the repository
// root: `npm run release:local` (see scripts/release-local.mjs).
const rules = [
  { type: 'feat', release: 'minor', title: '✨ Features' },
  { type: 'fix', release: 'patch', title: '🐛 Bug Fixes' },
  { type: 'perf', release: 'patch', title: '💨 Performance Improvements' },
  { type: 'refactor', release: 'patch', title: '🔄 Code Refactors' },
  { type: 'docs', release: 'patch', title: '📚 Documentation' },
  { type: 'chore', release: 'patch', title: '🛠️ Other changes' },
]

const sortMap = Object.fromEntries(
  rules.map((rule, index) => [rule.title, index])
)

const pkgRoot = 'packages/munim-xr'

/**
 * @type {import('semantic-release').GlobalConfig}
 */
module.exports = {
  branches: ['main', { name: 'next', prerelease: 'next' }],
  tagFormat: 'v${version}',
  plugins: [
    [
      '@semantic-release/commit-analyzer',
      {
        preset: 'conventionalcommits',
        releaseRules: [
          { breaking: true, release: 'major' },
          { revert: true, release: 'patch' },
        ].concat(rules.map(({ type, release }) => ({ type, release }))),
      },
    ],
    [
      // CHANGELOG.md is Keep a Changelog: the [Unreleased] entries become the
      // release, or the commits' notes when there are none.
      './scripts/release-changelog.mjs',
      {
        changelogFile: 'CHANGELOG.md',
        commitNotes: {
          preset: 'conventionalcommits',
          presetConfig: {
            types: rules.map(({ type, title }) => ({
              type,
              section: title,
            })),
          },
          writerOpts: {
            commitGroupsSort: (a, z) => sortMap[a.title] - sortMap[z.title],
          },
        },
      },
    ],
    ['@semantic-release/npm', { pkgRoot }],
    '@semantic-release/github',
    [
      '@semantic-release/git',
      {
        assets: [`${pkgRoot}/package.json`, 'package-lock.json', 'CHANGELOG.md'],
      },
    ],
  ],
}
