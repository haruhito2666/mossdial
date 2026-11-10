# Mossdial branding

<p align="center">
  <img src="mossdial-mark-512.png" width="128" alt="Mossdial mark">
</p>

The primary artwork is `mossdial-logo.png`: a 1254 × 1254 square with the moss-dial character on the
brand ink background `#111311`. The character is intentionally cropped at the left and bottom, so the
raw square should not be dropped into an adaptive-icon foreground.

| File | Use |
| --- | --- |
| `mossdial-logo.png` | Primary artwork and source for every derived mark |
| `mossdial-mark-512.png` | Transparent circular mark for README and general use |
| `mossdial-icon-512.png` | Square icon for store listings and non-Android contexts |

The Android launcher icon is derived from the primary artwork:

- Background: flat brand ink `#111311` (`app/src/main/res/drawable/ic_launcher_background.xml`).
- Foreground: the 1060 × 1060 crop at `(0, 70)` from the primary artwork, clipped to a circle and
  placed as a 72dp badge on the 108dp adaptive canvas. The 72dp badge matches the launcher's 72dp
  mask viewport, which keeps the artwork's straight left edge tangent to the mask and its cut bottom
  edge outside it. Density assets live in `app/src/main/res/drawable-*/ic_launcher_foreground.png`.
- Monochrome: the pale face and leaves with the green eyes and mouth as transparent holes, used by
  the Android 13 themed icon in `mipmap-anydpi-v33/`. The green body ring is deliberately omitted so
  the glyph stays legible when the launcher tints it.

Do not replace the adaptive foreground with the raw square logo: launchers mask the outer canvas and
would cut through the face and leaves. If the artwork changes, regenerate the foreground, monochrome
and 512px marks from the same crop so the launcher, store listing and README stay consistent.

Brand colours: moss green `#44A857`, pale green `#DFF4E3`, ink `#111311`.
