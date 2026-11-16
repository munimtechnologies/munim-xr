/**
 * React sends `null` for a prop that was removed between renders, e.g.
 * `continuousHitTest={enabled ? config : undefined}` going from a value to
 * `undefined` (or a prop passed as `null`). Nitro 0.37's `std::optional<T>`
 * converter only accepts `undefined`, so the Hybrid View props constructor
 * throws "Value is null, expected an Object" and React Native crashes the app
 * (on both platforms: the props are parsed in shared C++). Function props crash
 * the same way when a callback is removed.
 *
 * This routes every optional prop of the generated `Hybrid*Component.cpp`
 * through `nullable::fromRawValueOrNull<T>()`, which turns `null` into "unset"
 * (`std::nullopt`, the same as `undefined`) and otherwise defers to Nitro's
 * `ReactProp::fromRawValue`. Native setters then receive nil / null.
 *
 * Run after `nitrogen` (see the `codegen` script). Idempotent. Fails if the
 * generated code no longer has the expected shape.
 */
const path = require('node:path')
const fs = require('node:fs')

const MARKER =
  '// munim-xr: nullable optional props (scripts/patch-nitro-nullable-props.js)'
const INCLUDE_ANCHOR = '#include <NitroModules/ReactProp.hpp>\n'
const HELPER = `${MARKER}
// React sends \`null\` for removed props; Nitro's std::optional converter only
// accepts \`undefined\`. Treat \`null\` as unset instead of throwing.
#include <NitroModules/JSICache.hpp>
#include <optional>
#include <utility>

namespace margelo::nitro::munimxr::views::nullable {

  template <typename T>
  nitro::ReactProp<std::optional<T>> fromRawValueOrNull(const char* viewName,
                                                        const char* propName,
                                                        const facebook::react::RawProps& rawProps,
                                                        const nitro::ReactProp<std::optional<T>>& previousProp) {
    const facebook::react::RawValue* rawValue = nitro::RawPropsCompat::at(rawProps, propName);
    if (rawValue != nullptr) {
      auto [runtime, value] = static_cast<std::pair<facebook::jsi::Runtime*, facebook::jsi::Value>>(*rawValue);
      if (value.isNull()) {
        if (previousProp.isProvided() && !previousProp.get().has_value()) {
          // Already unset: keep the same entry so the native setter is not called again.
          return previousProp;
        }
        nitro::JSICacheReference cache = nitro::JSICache::getOrCreateCache(*runtime);
        return nitro::ReactProp<std::optional<T>>(std::optional<T>(std::nullopt),
                                                  cache.makeShared(facebook::jsi::Value::undefined()));
      }
    }
    return nitro::ReactProp<std::optional<T>>::fromRawValue(viewName, propName, rawProps, previousProp);
  }

} // namespace margelo::nitro::munimxr::views::nullable
`

// `name(nitro::ReactProp<std::optional<TYPE>>::fromRawValue(` — one prop per line.
const OPTIONAL_PROP =
  /^(\s+\w+\()nitro::ReactProp<std::optional<(.*)>>::fromRawValue\(/gm

/** Returns `{ source, patched }`; throws when the generated shape is unexpected. */
function patch(source, file = 'component') {
  let result = source
  if (!result.includes(MARKER)) {
    if (!result.includes(INCLUDE_ANCHOR)) {
      throw new Error(
        `patch-nitro-nullable-props: ${file}: ${INCLUDE_ANCHOR.trim()} not found`
      )
    }
    result = result.replace(INCLUDE_ANCHOR, `${INCLUDE_ANCHOR}\n${HELPER}`)
  }
  let patched = 0
  result = result.replace(OPTIONAL_PROP, (_, head, type) => {
    patched++
    return `${head}nullable::fromRawValueOrNull<${type}>(`
  })
  if (/ReactProp<std::optional<.*>>::fromRawValue\("/.test(result)) {
    throw new Error(
      `patch-nitro-nullable-props: ${file}: unpatched optional props remain`
    )
  }
  return { source: result, patched }
}

function main() {
  const viewsDir = path.join(
    __dirname,
    '..',
    'nitrogen/generated/shared/c++/views'
  )
  const files = fs
    .readdirSync(viewsDir)
    .filter((name) => /^Hybrid\w+Component\.cpp$/.test(name))
  if (files.length === 0) {
    throw new Error(
      'patch-nitro-nullable-props: no generated Hybrid*Component.cpp found'
    )
  }
  let total = 0
  for (const file of files) {
    const filePath = path.join(viewsDir, file)
    const original = fs.readFileSync(filePath, 'utf8')
    const { source, patched } = patch(original, file)
    if (!source.includes('nullable::fromRawValueOrNull<')) {
      throw new Error(
        `patch-nitro-nullable-props: ${file}: no optional props found`
      )
    }
    total += patched
    if (source !== original) fs.writeFileSync(filePath, source)
  }
  console.log(
    `patch-nitro-nullable-props: routed ${total} optional props through fromRawValueOrNull`
  )
}

module.exports = { patch, MARKER }

if (require.main === module) main()
