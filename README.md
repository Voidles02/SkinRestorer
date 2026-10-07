# SkinRestorer

SkinRestorer is a Paper plugin for restoring player skins on online-mode and offline-mode servers. It is maintained by `voidles02` and requires Java 25.

## Installation

1. Compile the project and place the resulting `SkinRestorer` JAR in the server's `plugins` folder.
2. Start or restart the Paper server.
3. Ensure the server can make outbound HTTPS requests to Mojang and to any hosts used for skin image URLs.

The plugin uses Paper's player profile API. It does not require a separate skin plugin or database.

## Commands

| Command | Description | Permission |
| --- | --- | --- |
| `/skin set <skinName> [player]` | Apply the skin belonging to a premium Minecraft account name. | `skinsrestorer.command.set` |
| `/skin url <url> [classic\|slim] [player]` | Apply a skin from a direct PNG URL. The model defaults to `classic`. | `skinsrestorer.command.url` |
| `/skin clear [player]` | Remove the saved custom skin and restore the player's original profile. | `skinsrestorer.command.clear` |
| `/skin update [player]` | Fetch the current skin again from its saved name or URL; uses the player's name if no skin source is saved. | `skinsrestorer.command.update` |
| `/skin random [player]` | Apply a randomly selected skin from the built-in account list. | `skinsrestorer.command.random` |

`/skins` is an alias for `/skin`. The optional `player` argument is for online players and requires `skinsrestorer.admin`. Console must specify a target player. For URL commands, place the model before the target when specifying both, for example `/skin url https://example.com/skin.png slim Steve`.

All `skinsrestorer.command.*` permissions default to `true`. `skinsrestorer.admin` defaults to operators. Grant or revoke individual command permissions with your permissions plugin as needed.

## Skin URL requirements

- Use a direct HTTP or HTTPS link to a PNG image hosted on a publicly accessible host.
- The image must be a Minecraft skin sized 64x64 or 64x32 and no larger than 10 MiB.
- The host must not resolve to a local or private network address.
- The server needs outbound access to resolve the host and download the image.

## Join behavior

- **Offline mode:** If a previously restored skin is saved, it is applied immediately, then refreshed asynchronously from its source name. Otherwise, the player's name is looked up asynchronously through Mojang and the result is saved for future joins. A failed first lookup falls back to the player's default profile; it does not kick the player.
- **Online mode:** Automatically discovered offline-mode skins are ignored. Only skins explicitly set with a skin command are reapplied on join.
- Successful skin changes refresh the player for current viewers. Network calls and storage writes run away from the main server thread; network work is bounded to prevent request bursts from consuming unbounded threads or queued memory.
- Mojang skin results are cached in memory for seven days. HTTP 429 responses honor `Retry-After` when available and trigger a shared backoff.

## Data and troubleshooting

Saved skins are stored in `plugins/SkinRestorer/skins.json`. This file is created and updated automatically; keep a backup before manually editing it. No player data is sent to a third-party service beyond the player-name lookup required for Mojang skin resolution or the URL explicitly supplied by an administrator/player.

If skins do not load, check that the server has outbound network and DNS access, that Mojang is reachable, and that URL skins meet the PNG and host requirements. Mojang rate limits can delay lookups; retry after the reported backoff interval. Skin restoration failures never intentionally disconnect a player.