/**
 * Removing an optional prop between renders makes React send `null`, which
 * Nitro's std::optional converter rejects ("Value is null, expected an
 * Object") and React Native turns into a fatal crash. These tests pin the
 * post-codegen patch that maps `null` to "unset" for every optional prop.
 */
const fs = require('node:fs')
const path = require('node:path')
const { patch, MARKER } = require('../scripts/patch-nitro-nullable-props')

const root = path.join(__dirname, '..')
const generated = fs.readFileSync(
  path.join(
    root,
    'nitrogen/generated/shared/c++/views/HybridXRViewComponent.cpp'
  ),
  'utf8'
)

/** Optional (`name?:`) props declared in the `XRViewProps` spec. */
function optionalSpecProps() {
  const spec = fs.readFileSync(
    path.join(root, 'src/specs/XRView.nitro.ts'),
    'utf8'
  )
  const body = spec.slice(spec.indexOf('export interface XRViewProps'))
  const block = body.slice(0, body.indexOf('\n}\n'))
  return [...block.matchAll(/^ {2}(\w+)\?:/gm)].map((match) => match[1])
}

const SAMPLE = `#include "HybridXRViewComponent.hpp"

#include <NitroModules/NitroHash.hpp>
#include <NitroModules/ReactProp.hpp>

namespace margelo::nitro::munimxr::views {
  HybridXRViewProps::HybridXRViewProps(const react::PropsParserContext& context,
                                       const HybridXRViewProps& sourceProps,
                                       const react::RawProps& rawProps):
    react::ViewProps(context, sourceProps, rawProps, filterObjectKeys),
    depthEnabled(nitro::ReactProp<bool>::fromRawValue("XRView", "depthEnabled", rawProps, sourceProps.depthEnabled)),
    detectionImages(nitro::ReactProp<std::optional<std::vector<XRDetectionImage>>>::fromRawValue("XRView", "detectionImages", rawProps, sourceProps.detectionImages)),
    onFrame(nitro::ReactProp<std::optional<std::function<void(const XRFrame& /* frame */)>>>::fromRawValue("XRView", "onFrame", rawProps, sourceProps.onFrame)) { }
}
`

describe('patch-nitro-nullable-props', () => {
  it('routes optional props (values, arrays, callbacks) through the null-safe helper', () => {
    const { source, patched } = patch(SAMPLE)
    expect(patched).toBe(2)
    expect(source).toContain(
      'detectionImages(nullable::fromRawValueOrNull<std::vector<XRDetectionImage>>("XRView", "detectionImages"'
    )
    expect(source).toContain(
      'onFrame(nullable::fromRawValueOrNull<std::function<void(const XRFrame& /* frame */)>>("XRView", "onFrame"'
    )
    // Required props keep Nitro's converter.
    expect(source).toContain(
      'depthEnabled(nitro::ReactProp<bool>::fromRawValue('
    )
    expect(source.split(MARKER)).toHaveLength(2)
  })

  it('is idempotent', () => {
    const once = patch(SAMPLE).source
    const twice = patch(once)
    expect(twice.source).toBe(once)
    expect(twice.patched).toBe(0)
  })

  it('fails loudly when the generated shape changes', () => {
    expect(() =>
      patch(SAMPLE.replace('#include <NitroModules/ReactProp.hpp>\n', ''))
    ).toThrow(/not found/)
  })

  it('covers every optional XRView prop in the committed bindings', () => {
    const optional = optionalSpecProps()
    // Guards the spec parsing itself: new props from this release must be in the list.
    expect(optional).toEqual(
      expect.arrayContaining([
        'mode',
        'continuousHitTest',
        'detectionImages',
        'onHitTestResult',
      ])
    )
    for (const name of optional) {
      expect(generated).toContain(`    ${name}(nullable::fromRawValueOrNull<`)
    }
    expect(generated).not.toMatch(
      /ReactProp<std::optional<.*>>::fromRawValue\("/
    )
    expect(generated).toContain('if (value.isNull())')
  })
})
