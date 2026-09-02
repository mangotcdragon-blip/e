# Decoy Guard

Hides your real DMs behind a set of decoy conversations. Locked by default:
your DM list only shows the channels you've configured as decoys, showing
fake content instead of their real messages. Type your unlock phrase into
any DM box and hit send (it's intercepted, never actually sent) to reveal
your real DM list and content. It auto re-locks the moment the app is
backgrounded.

## Setup (do this before enabling the plugin)

1. Build and run this fork on your device (see the main README).
2. Go to Settings > Plugins > Decoy Guard > gear icon to open its settings.
3. Set a real unlock phrase (something you won't type by accident, but can
   type casually enough not to look weird - e.g. a phrase you'd plausibly
   send anyway).
4. For each of the ~10 chats you want to keep visible as decoys, get its
   channel ID (long-press the chat / user > Copy ID - you may need
   Settings > General > Developer Mode enabled for the copy-ID option to
   show up) and add it to the JSON box:
   ```json
   {
     "123456789012345678": {
       "label": "friend name, just for your own reference",
       "messages": [
         { "fromMe": false, "text": "yo you free this weekend" },
         { "fromMe": true, "text": "yeah should be, whats up" }
       ]
     }
   }
   ```
   Order messages oldest to newest. Save.
5. Only now, enable the plugin from Settings > Plugins.

The plugin refuses to hide/block anything until both a real phrase and at
least one decoy channel are configured - this is intentional, to avoid a
self-lockout (hiding every DM with no visible chat left to type the unlock
phrase into).

## Test this yourself before trusting it

Do this before you rely on it around anyone:

- Reload the app. Confirm only your decoy chats show, with fake content.
- Type your unlock phrase into one of the visible decoy chats and send it.
  Confirm it unlocks (full DM list + real content reappear) and the phrase
  itself never actually sent.
- Background the app (home button) and reopen it. Confirm it's locked again.
- Have someone actually text you on a hidden (non-decoy) chat while locked.
  Reopen the app and confirm nothing about it is visible or was force
  opened by a notification tap.
- Send the unlock phrase again while unlocked and confirm it re-locks.

## What this can't hide

This is a JS-level patch inside the Discord app's own UI/state layer. It
cannot control things that happen outside that layer:

- **Push notification previews.** Android notifications for a new DM are
  often shown by system-level push handling before/independent of this
  patch running, so a hidden or decoy channel's contact could still trigger
  a notification banner showing their real name and message text. Turn off
  notification previews for those contacts (or generally) in Discord's own
  notification settings, and/or Android's "hide sensitive notification
  content on lock screen" setting, as a backstop.
- **Unread badge counts.** The DM section/app icon badge may still reflect
  real unread messages in hidden chats even though the chat itself isn't
  listed.
- **Discord's own search**, mention pings, and anywhere else the app reads
  message data through a code path this plugin doesn't patch.
- Anyone with real device-forensics capability (not just opening the app and
  looking around).

## Internals note for whoever maintains this later

The store/action names this relies on (`PrivateChannelSortStore`,
`ChannelStore`, `MessageActions.sendMessage`/`fetchMessages`,
`MESSAGE_CREATE`/`LOAD_MESSAGES_SUCCESS` Flux actions,
`FluxDispatcher.setInterceptor`) are long-standing, well-precedented Discord
client conventions used throughout the Vendetta/Bunny plugin ecosystem, not
verified against a live decompiled build of this specific Discord version.
If something silently doesn't work (e.g. `fetchMessages` isn't the real name
in a future Discord update), the safe failure direction is already built in:
worst case a channel doesn't refresh until you manually reopen it, or the
plugin simply does nothing (see `isConfigured()`), never "shows real content
while claiming to be locked" - keep any future changes to this failure
direction if you touch this file.
