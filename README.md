# MessageLogger (Kettu plugin)

Logs messages in the channels your Discord client receives, **including edits and deletions**, and exports any channel as a `.txt` file. Made for moderating big servers from mobile.

## Install

In Kettu: **Settings → Kettu → Plugins → +** and paste:

```
https://raw.githubusercontent.com/mangotcdragon-blip/e/ccr-c570d7fe-igxne9/dist/message-logger/
```

(If this branch gets merged into `main`, replace `ccr-c570d7fe-igxne9` with `main`. Keep the trailing `/`.)

## What it logs

- New messages, with author name, username, user ID, timestamp, replies, attachments (links), stickers and embed text
- Edits: the old version is kept, so you can see what a message said before it was edited
- Deletions: the message stays in the log and is marked `[DELETED <time>]`, including bulk deletes/purges
- History you scroll through (optional): opening a channel captures the messages Discord loads

Settings let you choose: all servers or only selected ones, skip individual servers/channels, DMs on/off (off by default), bots on/off, and the max messages kept per channel (default 10,000; the oldest are dropped first).

## Exporting

**From the channel itself:** type `/exportlog` in any channel. Optional arguments:
- `range`: last hour / 24 hours / 7 days / 30 days / everything
- `user`: only one user (ID, username or part of a name)
- `deleted`: only deleted messages

**From settings:** open the plugin settings, tap a channel under *Logged channels*, pick a range or filters, then choose **Share / save .txt**, **Copy to clipboard** or **Save file only**.

On iOS the share sheet gets a real `.txt` file, so *Save to Files* works. On Android it shares the text, so pick Drive, a file manager, a notes app, or wherever you want it saved. Very large exports (over about 400k characters) are shared in parts because Android can't send more than that at once. A copy is always saved in Discord's app storage under `MessageLogger/exports/` as well.

Example output:

```
Big Server / #general
Channel ID: 123456789012345678
Exported: 2026-10-01 14:03:22
Range: Last 24 hours
Messages: 2
============================================================

[2026-10-01 13:58:10] Some Name (@someuser) [111111111111111111]
    hello

[2026-10-01 14:01:42] Other (@spammer) [222222222222222222] [DELETED 2026-10-01 14:02:05] [EDITED x1]
    > replying to @someuser: hello
    (before edit at 2026-10-01 14:01:55): original text here
    (current):
    edited text
    [attachment] https://cdn.discordapp.com/attachments/...
```

## Limitations

- Only messages your client receives get logged, so nothing is captured while Discord is closed or Kettu isn't running. Discord may also stop streaming live messages for very large servers you don't have open; turn on *Also log history you scroll through* and open those channels to fill the gaps.
- Attachments are saved as links, not files. Discord CDN links expire after a while, so export soon if you need the images.
- Logs are stored on your device only.

## Building

```
npm install
npm run build
```

The source is in `plugins/message-logger/src`, and the build goes to `dist/message-logger`.
