# Third-party notices

## cat3399/blbl

This Android application is derived from https://github.com/cat3399/blbl.
The initial upstream reference is `cd09bd903e6d0c22f5986580c2105796d8df6471`.
The upstream reference did not include a LICENSE file. Existing upstream code,
assets, acknowledgements and author restrictions retain their original status;
the complete original README is retained below the fork documentation.

Publishing this fork and its APKs is not a statement that additional permission
has been obtained from the upstream author. This fork does not relicense all of
blbl under MIT. See [LICENSING.md](LICENSING.md) for the scope of new contributions.

## Bilibili-thread-ripper

The native Android Media3 playback accelerator adapts the concurrent HTTP Range
download and CDN selection ideas from Bilibili-thread-ripper, especially
`src/range-core.js`, `src/cdn-resolver.js`, and `src/idm-downloader.js`.

- Upstream: https://github.com/MrTangLuyao/Bilibili-thread-ripper
- Initial native reference commit: `ea3e940c400ceac338f3eea00281249c96966d0c`
- Copyright (c) 2026 Bilibili-thread-ripper contributors
- License: MIT; the complete text is in
  [third_party/Bilibili-thread-ripper-LICENSE](third_party/Bilibili-thread-ripper-LICENSE)
  and is also included in APK assets.

This is a native Android adaptation. Browser player takeover, browser settings,
and experimental live acceleration are not included. The same bounded native
transport is used by the television and phone builds.

Automated tracking keeps `observed_sha` separate from `native_reference_sha` in
`.github/upstream-state.json`. Observing a newer browser-script commit does not
mean its behavior has been ported to Kotlin. A BTR-only update produces an
adaptation report (`.github/btr-adaptation-report.md`), not a release of the unchanged native core. The native
reference advances only when the relevant Kotlin adaptation has been completed
and tested. Release notes must identify the actual native reference used.

## Original project dependencies and acknowledgements

The retained upstream README lists Bilibili API collect, BBLL, PiliPlus,
IjkPlayer and other upstream references. Their original rights and applicable
licenses are unaffected by this fork. The license for this fork's new original
contributions is [LICENSE-BTR](LICENSE-BTR); it does not replace upstream or
dependency licenses.
