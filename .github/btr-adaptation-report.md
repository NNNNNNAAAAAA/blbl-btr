# BTR native adaptation report

- Native Kotlin reference: `ea3e940c400ceac338f3eea00281249c96966d0c`
- Latest observed BTR commit: `0ca4173f65c068d2446eb6393f72864f94f380a3`
- Status: PENDING MANUAL NATIVE ADAPTATION
- Compare: https://github.com/MrTangLuyao/Bilibili-thread-ripper/compare/ea3e940c400ceac338f3eea00281249c96966d0c...0ca4173f65c068d2446eb6393f72864f94f380a3

Observing upstream JavaScript does not adapt or execute it in Android. BTR-only changes produce this report; they do not produce an APK. Port applicable changes to Kotlin, add/adjust tests, and explicitly advance native_reference_sha in the adaptation commit. An app-code push then runs the release gates. A blbl update may release independently while this report remains pending.

## Changed BTR files (2; first 300 shown)

```json
[
  "CHANGELOG.md",
  "user_scripts/bilibili-thread-ripper.user.js"
]
```
