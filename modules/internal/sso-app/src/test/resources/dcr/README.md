# Vendored DetailedCombatResults bytecode (test-only)

These three compiled classes are extracted verbatim from **DetailedCombatResults v5.4.2**
(`StarSectorDetailedCombatResults.jar`). `DcrBatchMixinAnchorTest` reads them as resource bytes to verify
that the call sites targeted by the DCR Mixins still exist in the mod's real bytecode. Vendoring lets that
test run in CI, where the game install / DCR jar is absent.

- `data/scripts/combatanalytics/SerializationManager.class`
- `data/scripts/combatanalytics/DetailedCombatResultsModPlugin.class`
- `data/scripts/combatanalytics/util/CompressionUtil.class`

They live under the `dcr/` resource prefix so they are only ever read as resource bytes and never loaded
as `data.scripts.*` classes.

DetailedCombatResults is MIT-licensed; the notice is preserved in `DCR-LICENSE.txt`.

**On DCR update:** if a future DCR version changes these classes' shapes, re-extract them and re-run
the test; a failure here is the intended early signal that the Mixin anchors need re-verification.
