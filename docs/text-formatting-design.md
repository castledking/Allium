# Allium Text Formatting & Animation Design

Status: design specification, 2026-11-05. Authoritative scope and sequencing live in
[the Roadmap](roadmap.md) and [the TODO](../todo.md).

---

## 1. Overview & Slice Split

Allium owns its text formatting, color parsing, message sound playback, and animated
text subsystem in core, retiring Takion (`me.croabeast.takion:shaded`) as a
dependency.

Implementation is split into two vertical slices:

- **S01.5a — Static Formatting & Retire Takion (M1, immediate):**
  - Legacy `&`/`§` color and style codes (`0-9a-f`, `k-o`, `r`).
  - Hex colors (`&#RRGGBB`, `#RRGGBB`, and `§x§r§r§g§g§b§b`).
  - Multi-stop `<gradient:#RRGGBB:#RRGGBB[:#RRGGBB...]>` and `<rainbow[:phase]>` tags,
    including nested style codes (`&l`, `&o`, etc.) and reset handling.
  - Platform output behind a clean adapter: Adventure `Component`s on Paper (with no
    Paper/Adventure types in shared signatures, fields, or eagerly loaded classes)
    and legacy `§x` strings on Spigot, producing identical visible results on both
    paths.
  - `MessageManager` and locale delivery routed through the formatter, with
    Allium-owned sound playback.
  - Complete removal of `me.croabeast.takion:shaded` from `build.gradle.kts`.
- **S01.5b — Deterministic Text Animation Engine (deferred until the first animated target):**
  - Activated when implementing the first animated display target (tab list name,
    nametag / scoreboard team, boss bar, or hologram / text-display entity).
  - Supplies per-glyph colors via the pure function
    `color(glyphIndex, length, timeMs, seed) -> RGB` into the formatter's glyph
    coloring hook without rewriting the parser or renderer.

---

## 2. Static Formatter Architecture (S01.5a)

### 2.1 Glyph Color Hook for S01.5b Extensibility

Rather than hardcoding gradient/rainbow math inside string concatenation, the
formatter parses styled runs into spans and colors each visible glyph (code point)
through a functional color source:

```java
@FunctionalInterface
public interface GlyphColorSource {
    /**
     * Returns the 24-bit RGB color (0xRRGGBB) for glyph at {@code glyphIndex}
     * within a span of {@code length} visible glyphs.
     */
    int colorAt(int glyphIndex, int length);
}
```

In **S01.5a**, static `<gradient:...>` and `<rainbow:...>` tags bind a
`GlyphColorSource` with `timeMs = 0, seed = 0`. In **S01.5b**, animated effects bind
`color(glyphIndex, length, timeMs, seed) -> RGB` directly to `GlyphColorSource`
without changing the span parser or platform emitters.

### 2.2 Supported Syntax

1. **Legacy Codes (`&` and `§`):**
   - Standard 16 colors (`0`–`9`, `a`–`f`, case-insensitive).
   - Decorations: `&k` (obfuscated), `&l` (bold), `&m` (strikethrough),
     `&n` (underline), `&o` (italic).
   - Reset: `&r` resets active color and decorations.
   - Standard Minecraft rule: applying a new color code (`&0`–`&f` or hex) clears
     active decorations unless re-applied after the color, while decorations inside a
     `<gradient>` or `<rainbow>` span persist across the per-glyph colors of that span.
2. **Hex Colors:**
   - `&#RRGGBB` (6 hex digits, case-insensitive).
   - `§x§R§R§G§G§B§B` / `&x&R&R&G&G&B&B` (Bungee/Spigot 14-char hex escape).
   - `<#RRGGBB>` and `</#RRGGBB>` tag forms.
3. **Gradients:**
   - `<gradient:#RRGGBB:#RRGGBB[:#RRGGBB...]>text</gradient>` (2 or more hex stops).
   - For a span of `L` visible characters:
     - If `L == 0`: emits nothing.
     - If `L == 1`: uses stop `0` (`stops[0]`).
     - If `L > 1` with `S` stops (`S >= 2`): normalized position `t = glyphIndex / (double) (L - 1)`
       mapped across `S - 1` segments: `segment = min((int) floor(t * (S - 1)), S - 2)`,
       `localT = (t * (S - 1)) - segment`, linearly interpolating RGB channels
       `round(c0 + (c1 - c0) * localT)`.
4. **Rainbow:**
   - `<rainbow>` or `<rainbow:phase>` (optional float phase in `[0, 1)` or degrees,
     plus optional `!` reverse marker) closed by `</rainbow>`.
   - Maps glyph index `j` of `L` (`t = L <= 1 ? 0.0 : (double) j / L`) onto the HSV
     hue circle `(t + phase) % 1.0` with `S = 1.0, V = 1.0`.
5. **Nesting & Reset:**
   - Closing a `</gradient>` or `</rainbow>` restores the enclosing span's color
     source (or default uncolored state).
   - `<reset>` or `&r` inside or outside tags resets active color and decorations.

### 2.3 Dual Output Paths & Visual Parity

Both output paths consume the exact same parsed `StyledText` intermediate
representation:

- **Spigot Legacy Path (`LegacyTextEmitter`):**
  - Emits `§x§r§r§g§g§b§b` (lowercase hex digits) for 24-bit RGB colors, `§0`–`§f`
    for named legacy colors, and `§k`–`§o` / `§r` for decorations, minimizing
    redundant codes between adjacent glyphs with identical state.
- **Paper Adventure Path (`AdventureTextAdapter`):**
  - Isolated behind a runtime capability check so shared classes never reference
    `net.kyori.adventure.text.Component` in method signatures or fields.
  - Builds a root `Component.empty()` with child `Component.text(...)` nodes carrying
    the exact `TextColor.color(rgb)` and explicit `TextDecoration` states matching
    the legacy emitter.

---

## 3. Animation Contract (S01.5b)

- **Pure Deterministic Evaluation:** Every animated effect is a pure, deterministic
  function:
  `color(glyphIndex, length, timeMs, seed) -> RGB`
- **Single Clock & Bounded Scheduler:** All animated targets share one monotonic
  clock and one bounded scheduler tick at a configurable frame rate (default 100 ms /
  2 ticks). Never spawn per-player timers or per-frame `runTask` calls.
- **Frame Caching & Delta Pushes:** Rendered frames are cached per
  `(text, effect, frame)` and pushed to viewers/targets **only** when the rendered
  output actually changes from the last sent value.
- **Color Interpolation:** Support standard linear RGB interpolation and optional
  perceptual **OKLab** interpolation between stops.
- **Supported Targets:** Tab list name, nametag / scoreboard team prefix & suffix,
  boss bar titles, and hologram / text-display entities where present. Ordinary
  chat messages are rendered statically once when sent.

### 3.1 Initial Effects (Behavioral Specification Only)

- **`pulse`:** Picks a random glyph index `p` and flash color `c` from `palette[1..]`
  (with `palette[0]` as `base`), seeded deterministically per target (`seed` +
  cycle index). `intensity` starts at `1.0` and decreases by `0.05` per frame
  (at default 100 ms frame duration). For each glyph `j`:
  `weight = clamp(intensity - 0.1 * |p - j|, 0.0, 1.0)` (explicitly clamping the
  weight to `[0, 1]`, never relying on RGB channel clamping), and output color is
  `lerp(base, c, weight)`. When `intensity < 0.1`, advance to the next cycle with a
  new seeded `p` and `c`.
- **`shift`:** Discrete or blended palette stepping where glyph `j` at frame `f`
  samples `palette[(j + f) % n]`, with optional smooth interpolation between
  adjacent palette stops.
- **`sparkle`:** Deterministic per-frame pseudo-random roll (`hash(seed, frame, j)`)
  against a configurable probability; on hit, glyph `j` takes a random color from
  the palette, otherwise `base`.
- **`phase-scroll` gradient / rainbow:** Animates the phase offset of a multi-stop
  gradient or rainbow across `[0, 1)` (or `[-1, 1]` for MiniMessage compatibility)
  as a pure function of `(glyphIndex, length, timeMs)`.

---

## 4. Reference Audit: UnlimitedNametags Phase Animation

Source inspected: `/mnt/storage/devops/UnlimitedNametags` (`PlaceholderManager.java`,
`Formatter.java`, `GlowOverride.java`, `GlowOverrideComputer.java`).

### 4.1 How UnlimitedNametags Implements Phase Animation

1. **Three Separate 1-Tick Async Timers (`PlaceholderManager#startIndexTask`):**
   - **MiniMessage Gradient Phase (`miniGradientIndexBD`):** Runs every 1 tick
     (`runTaskTimerAsynchronously(..., 0, 1)`), incrementing a `BigDecimal` by `0.1`
     from `-1.0` to `1.0` and formatting via `DecimalFormat("#.#")` into two string
     entries in a `ConcurrentHashMap`:
     - `#phase-mm-g#` -> `df.format(miniGradientIndexBD)` (`-1.0` .. `1.0`)
     - `#-phase-mm-g#` -> `df.format(miniGradientIndexBD.negate())` (`1.0` .. `-1.0`)
   - **MiniMessage Rainbow Phase (`mmIndex`):** A second 1-tick async task decrements
     an integer `mmIndex` from `10` down to `1` and writes:
     - `#phase-mm#` -> `String.valueOf(mmIndex)`
     - `#-phase-mm#` -> `String.valueOf(11 - mmIndex)`
   - **MineDown Phase (`index`):** A third 1-tick async task decrements `index` from
     `16777215` down to `1` and writes `#phase-md#` / `#-phase-md#`.
2. **String Replacement + Reparse on Every Line (`PlaceholderManager#applyPlaceholders`):**
   - For every player and every nametag line on each refresh cycle, it performs
     literal `String#replace(entry.getKey(), entry.getValue())` for all 6 phase keys
     to synthesize a new MiniMessage tag (e.g. `<gradient:#ff0000:#00ff00:0.3>`),
     passes the mutated string to `MiniMessage#deserialize`, and stores the resulting
     `Component` in a 2-minute `ExpiringMap<String, Component>`.
3. **Entity Glow Animation (`GlowOverrideComputer`):**
   - Uses a monotonic tick counter (`tickCounter.incrementAndGet()`) alongside wall
     clock `System.currentTimeMillis()`.
   - Rainbow glow computes hue via `((elapsedSeconds * speed * 360.0) % 360.0)` and
     converts HSV to nearest NamedTextColor.
   - Gradient glow steps discretely via `(int) ((monotonicTick / stepInterval) % colors.size())`.

### 4.2 Design Takeaways for Allium

Allium avoids UnlimitedNametags' three separate 1-tick async timers, `BigDecimal`
string-formatting churn, and repeated string-level MiniMessage reparsing by:

1. Computing phase directly inside the pure `color(glyphIndex, length, timeMs, seed)`
   evaluator on Allium's single shared animation clock.
2. Parsing the template AST once and evaluating per-glyph colors directly per frame
   with bounded `(text, effect, frame)` caching and delta-only target updates.
