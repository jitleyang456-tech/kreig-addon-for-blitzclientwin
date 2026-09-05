# Kreig Addon — Module Reference

This addon extends the Blitz example addon with five modules covering respawn-anchor combat,
reactive self-defense, ender pearl throw assistance, and totem-pop notifications. Make sure to download from the official blitz client website (https://blitzclient.win/) for safety and compatability.

## Modules

### AnchorMacro

Fully automated respawn-anchor sequence: place → charge with one glowstone → explode, then disables
itself. No obsidian shield — the anchor lands wherever you're looking and the explosion isn't
directed away from you, so this is the "just detonate it" version rather than a self-protecting one.

**Settings**
| Setting | Default | Notes |
|---|---|---|
| Action Delay (ticks) | 3 | Wait between each step so the server has time to process the previous one. |

**Placement correctness:** resolves the actual landing position using the clicked block's
replaceability — snow layers, fire, tall grass, and similar are replaced in place rather than
offset onto a face, matching vanilla's own `ItemPlacementContext` behavior. Getting this wrong
doesn't change where the real anchor lands (the server does that regardless), but it does break the
module's own bookkeeping of where to look for it afterward, which is what earlier caused false
"placement failed" messages and wrong charge/explode targeting on snow and fire.

---

### SafeAnchorMacro


Same place → charge → explode automation as `AnchorMacro`, but places an obsidian block first and
puts the anchor one block *further out along the same horizontal line* — player → obsidian → anchor
— instead of stacking it on top. The obsidian sits between the player and the anchor, so it absorbs
the blast on the player's side while the anchor's far side stays open toward whatever's beyond it.

**Settings**
| Setting | Default | Notes |
|---|---|---|
| Action Delay (ticks) | 3 | Wait between placement/charge/explode steps. |
| Rotation Delay (ticks) | 1 | Wait after each rotation before the interaction fires, so the look packet lands before the interact packet. |

**Key behaviors:**
- **Real camera rotation, not silent.** Before placing the anchor, charging, or exploding, the
  module sets the player's actual yaw/pitch toward the target — this is a visible camera snap each
  time, not a packet-only fake-look. A server (or anti-cheat) resolving reach/direction sees a
  placement that matches where the player is actually facing.
- **Doesn't hardcode a single face.** Rather than assuming the obsidian's far face is always
  clickable, `findValidPlacementHit`/`findValidInteractionHit` search all six neighboring directions
  for one that's solid/exposed and within reach, preferring the ideal direction first and falling
  back through the rest. Aborts cleanly with a chat message if nothing works, restoring the
  previously-held item slot.
- **Same replaceable-block handling as AnchorMacro** for where the obsidian itself lands.

**Not yet handled:** face-finding checks reach and whether a face is exposed, but doesn't raycast for
obstructions in between — a technically-in-range face could still be blocked by another block.

---

### FeetPlaceModule

Reactive self-defense: places an obsidian block directly at your feet (in the direction you're
facing) to absorb explosion damage. Runs in one of two modes:

- **Smart Mode** *(default on)* — only places when externally triggered via `triggerSmartPlace(yLevel)`,
  e.g. from a mixin hooking into another player's or your own anchor detonation. `Only High Ground`
  restricts triggering to when you're above the given Y level, i.e. actually in the blast's vertical
  path.
- **Dumb Mode** — places continuously on a cooldown as long as a valid spot exists, no external
  trigger needed.

**Settings**
| Setting | Default | Notes |
|---|---|---|
| Smart Mode | true | Off = Dumb Mode (continuous placement on cooldown). |
| Only High Ground | true | Smart Mode only: require the player to be above the trigger's Y level. |
| Smooth Rotation | true | Turn gradually toward the placement target instead of snapping instantly. |
| Smooth Speed (°/tick) | 10.0 | Turn rate when Smooth Rotation is on. |
| Range | 8.0 | Max distance to the candidate placement block. |
| Cooldown (s) | 0.5 | Dumb Mode only: minimum time between placements. |

Also exposes `onSelfPlaceAnchor(pos)` / `isSelfPlacedAnchor(pos)` and a public static `INSTANCE`, the
same pattern `TotemPopModule` uses for its mixin — intended for a mixin to call `triggerSmartPlace`
when it detects an anchor (yours or an opponent's) about to go off nearby. That mixin isn't included
in this set of files; wiring it up is the remaining step to connect FeetPlace to the anchor modules
above.

---

### PearlLowestSpot


While holding an ender pearl, continuously simulates candidate throw arcs (stepping pitch from
`MIN_PITCH` to `MAX_PITCH`) using an approximation of vanilla pearl physics (gravity `0.03`/tick, drag
`0.99`/tick) to find the solid landing block that maximizes fall distance within reach — the "best
clutch spot" nearby. On right-click with a pearl in hand, it snaps your look direction to that
computed landing spot instead of wherever you were actually aiming.

**Settings**
| Setting | Default | Notes |
|---|---|---|
| Max Distance | 60.0 | Furthest a candidate landing spot can be from the player. |
| Minimum Fall (blocks) | 4.0 | Pearls below this fall distance aren't redirected — avoids hijacking short, deliberate throws. |

Recomputes every `RECOMPUTE_INTERVAL_TICKS` (6) ticks rather than every tick, and picks the
candidate with the greatest drop, breaking ties by horizontal distance (closer wins).

---

### TotemPopNotify


Announces totem of undying pops — yours, and optionally anyone else's in range — with a running
per-life pop count. Detection lives in a Fabric mixin (`ClientPlayNetworkHandlerMixin`) injecting
into `onEntityStatus`, filtering for status `35` (the totem animation) and forwarding the entity to
`TotemPopModule.onTotemPop(entity)`.

**Settings**
| Setting | Default | Notes |
|---|---|---|
| Self | true | Announce your own totem pops. |
| Others | true | Announce others' totem pops within Range. |
| Action Bar | false | Also show the message in the action bar, not just chat. |
| Range | 30.0 | Max distance for announcing others' pops. |
| Prefix | `[Totem]` | Editable tag text. |
| Prefix Color | gold | ARGB, masked to RGB on render. |
| Message Color | white | ARGB, masked to RGB on render. |

**Known quirks it corrects for:**
- **Per-life reset without a death event.** There's no death event in the API jar, so a "new life" is
  detected by noticing that the `LivingEntity` object reference for a given UUID changed — Minecraft
  constructs a new entity instance on respawn even though the UUID stays the same.

## Mixins required

| Mixin | Needed by |
|---|---|
| `ClientPlayNetworkHandlerMixin` (`onEntityStatus`, status 35) | TotemPopNotify |
| *(not included)* anchor/detonation-detection mixin | FeetPlaceModule's `triggerSmartPlace` hook — needs to be written and wired to call it |

Any addon using a mixin needs its own `mixins.json` and a `"mixins"` entry in `fabric.mod.json`, same
as the base example addon's totem-pop pattern.

## Licence

MIT, matching the base example addon.
