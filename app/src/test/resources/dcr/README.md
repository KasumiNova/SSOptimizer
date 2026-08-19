# Vendored DetailedCombatResults bytecode (test-only)

These three compiled classes are extracted verbatim from **DetailedCombatResults v5.4.2**
(`StarSectorDetailedCombatResults.jar`) and used **only by `DcrRealBytecodePatchTest`** to verify
that the DCR ASM processors apply correctly to the mod's real bytecode (constant pool, method shapes,
descriptors) — not just to hand-written fixtures. Vendoring lets that test run in CI, where the game
install / DCR jar is absent.

- `data/scripts/combatanalytics/SerializationManager.class`
- `data/scripts/combatanalytics/DetailedCombatResultsModPlugin.class`
- `data/scripts/combatanalytics/util/CompressionUtil.class`

They live under the `dcr/` resource prefix so the test classloader treats them as **resource bytes**,
never loading them as `data.scripts.*` classes (the test fixtures occupy those names).

DetailedCombatResults is MIT-licensed; the notice is preserved in `DCR-LICENSE.txt`.

**On DCR update:** if a future DCR version changes these classes' shapes, re-extract them and re-run
the test; a failure here is the intended early signal that the transform needs re-verification.
