# Artisan's Eye

English | [简体中文](README.md)

A **TerraFirmaCraft** addon: forging experience enhancement. Client-side only, zero mixins — works out of the box in singleplayer.

## Features

- **Numeric work bar** — the target offset and current offset are shown live at the right end of the anvil work bar.
- **Step value hints** — hovering a forging step button shows its exact work offset: red for negative, green for positive.
- **Shortest-path hints** — solves the shortest valid hit sequence to completion in real time; the recommended button pulses with a breathing outline and the remaining hit count is displayed.
- **Charcoal forge glance** — aiming at a charcoal forge shows a single strip with the fuel slots and the forge temperature
- **Small vessel insight** — adds a text list of the remaining solid contents to the item tooltip of heated small vessels

## Environment

| Dependency | Version |
|---|---|
| Minecraft | 1.21.1 |
| NeoForge | `[21.1.197,)` (developed against 21.1.250) |
| TerraFirmaCraft | `[4.1.0, 4.3)` (developed against 4.2.10) |

## Building

```bash
./gradlew build      # use gradlew.bat on Windows
./gradlew runClient  # launch the dev client (loads TFC + Patchouli automatically)
```

## License

MIT
