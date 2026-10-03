/**
 * Nitrogen 0.36.x converts `std::vector<double>` to `[Double]` in the
 * generated Swift with `vector.map({ __item in __item })`. With Xcode 26's
 * Swift toolchain the `CxxRandomAccessCollection` conformance for that
 * instantiation is unavailable, so the generated code does not compile
 * ("value of type 'std.vector<CDouble>' has no member 'map'") — this breaks
 * every Xcode 26 build, because `XRPose.matrix` is a `number[]`. Xcode 27
 * compiles it, which is why device builds never showed it.
 *
 * This rewrites those conversions (inside `[Double]` / `[Double]?` getters and
 * setters) to an explicit `size()` / subscript loop, which needs no
 * conformance. Vectors of structs are unaffected and left alone.
 *
 * Run after `nitrogen` (see the `codegen` script). Idempotent. Fails if a
 * `[Double]` conversion still uses `.map`, so a Nitro upgrade that changes the
 * generated shape cannot silently reintroduce the break.
 */
const path = require('node:path')
const fs = require('node:fs')

const swiftDir = path.join(__dirname, '..', 'nitrogen/generated/ios/swift')
const HELPER_NAME = '__nitroDoubleVectorToArray'
const VECTOR_TYPE = 'margelo.nitro.munimxr.bridge.swift.std__vector_double_'
const HELPER = `
/// munim-xr: see scripts/patch-nitro-double-vectors.js (Xcode 26 cannot \`.map\` a std::vector<double>).
@inline(__always)
fileprivate func ${HELPER_NAME}(_ vector: ${VECTOR_TYPE}) -> [Double] {
  let count = Int(vector.size())
  var result: [Double] = []
  result.reserveCapacity(count)
  var index = 0
  while index < count {
    result.append(vector[index])
    index += 1
  }
  return result
}
`
const MAP_CALL = /([A-Za-z_][\w.]*)\.map\(\{ __item in __item \}\)/g
// Getter or setter blocks typed `[Double]` / `[Double]?`.
const DOUBLE_BLOCK =
  /^\n {2}(?:@inline\(__always\)\n {2})?(?:public final )?var \w+: (?:bridge\.std__(?:optional_)?vector_double_+|\[Double\]\??) \{/

const bridgeHeader = fs.readFileSync(
  path.join(
    __dirname,
    '..',
    'nitrogen/generated/ios/NitroMunimXr-Swift-Cxx-Bridge.hpp'
  ),
  'utf8'
)
if (
  !bridgeHeader.includes('using std__vector_double_ = std::vector<double>;')
) {
  throw new Error(
    'patch-nitro-double-vectors: bridge type std__vector_double_ not found'
  )
}

let patched = 0
const leftovers = []
for (const file of fs
  .readdirSync(swiftDir)
  .filter((name) => name.endsWith('.swift'))) {
  const filePath = path.join(swiftDir, file)
  const original = fs.readFileSync(filePath, 'utf8')
  let source = original
    .split(/(?=\n {2}(?:@inline\(__always\)\n {2})?(?:public final )?var )/)
    .map((chunk) => {
      if (!DOUBLE_BLOCK.test(chunk)) return chunk
      return chunk.replace(MAP_CALL, (_, expression) => {
        patched++
        return `${HELPER_NAME}(${expression})`
      })
    })
    .join('')
  if (
    source.includes(`${HELPER_NAME}(`) &&
    !source.includes(`func ${HELPER_NAME}(`)
  ) {
    source += HELPER
  }
  if (source !== original) fs.writeFileSync(filePath, source)

  for (const chunk of source.split(
    /(?=\n {2}(?:@inline\(__always\)\n {2})?(?:public final )?var )/
  )) {
    if (DOUBLE_BLOCK.test(chunk) && MAP_CALL.test(chunk)) {
      leftovers.push(`${file}: ${chunk.trim().split('\n')[0]}`)
    }
    MAP_CALL.lastIndex = 0
  }
}
if (leftovers.length > 0) {
  throw new Error(
    `patch-nitro-double-vectors: [Double] conversions still use .map:\n${leftovers.join('\n')}`
  )
}
console.log(
  `patch-nitro-double-vectors: rewrote ${patched} std::vector<double> conversions`
)
