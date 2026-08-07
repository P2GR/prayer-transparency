# Prayer Transparency

Makes the overhead prayer icons floating above players semi-transparent, with a
configurable transparency level.

## Features

- **Transparent overhead prayers** - master on/off.
- **Overhead prayer transparency** - 0 (fully visible) .. 10 (almost
  invisible; 10 = 250/255 transparent).
- **Local player only** - restrict the effect to your own prayer icon.
- **Icon vertical offset** - moves the prayer icon up (positive) or down
  (negative) from its normal height, in local units (128 = one tile).
- **Prayer icon style** - `Game` redraws the game's own icon (which includes
  the beige circular backing baked into the overhead sprite); `Clean` replaces
  it with the matching prayer book icon (no circle).
- **Debug logging** - per-frame detection logs and on-screen debug boxes.

## How it works

OSRS draws overhead prayers as 2D sprites into the client's software frame
buffer in a short pass between the 3D scene and the interfaces. The plugin uses
two overlay layers that bracket that pass:

- `ABOVE_SCENE` - captures the scene behind each prayer icon.
- `UNDER_WIDGETS` - erases the game's icon and redraws it (or the clean prayer
  book icon) at the configured opacity/position, in place, so it always lands
  exactly where the game drew it.

This works in both software and GPU rendering modes.

## Quick setup

1. Enable the plugin.
2. Set your transparency level.
3. Optionally enable **Clean** icon style to drop the beige circle, or set a
   vertical offset to float the icon higher/lower.

## Notes

- The clean icon is loaded from the game's sprite index
  (`SpriteID.Prayeron.*`). Curse and combined head icons have no clean
  equivalent and fall back to the game icon.
- The plugin manipulates the client's own frame buffer; it does not conflict
  with other plugins.
