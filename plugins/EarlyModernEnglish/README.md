# Early Modern English

Rewrites every message you send (and edit) into Early Modern English before it reaches Discord.

```
Hello, how are you?                 -> Good morrow, how dost thou fare?
I don't know what you mean lol      -> I know not what thou meanest 'tis most droll
If you want my advice, go away      -> If thou wantest mine advice, begone
He says he likes my outfit          -> He saith he liketh mine outfit
Have you seen my apple?             -> Hast thou seen mine apple?
```

It runs on your device with a fixed set of rules: no network calls, no AI. It handles thou/thee/thy/thine, verb endings (-est, -eth), "do not know" -> "know not", contractions, 'tis/'twas, common vocabulary (aye, nay, ere, perchance, wherefore, prithee) and chat slang. Being rule-based it will sometimes pick "thou" where "thee" fits or the other way round.

Left untouched: code blocks, inline code, links, mentions, channel links, custom emoji, timestamps and `:emoji:` shortcodes.

## Settings

- **Rewrite my messages**: master switch.
- **Rewrite edits too**: also translate when you edit a message.
- **Conjugate verbs**: "thou knowest", "he liketh", "I know not". Off keeps only the auxiliaries (art, dost, hast, wilt).
- **Translate slang**: lol, idk, u, bro, brb and so on.
- **Try it**: type a sentence and see the result live.

Start a message with a backslash (`\`) to send it unchanged. The backslash is removed.
