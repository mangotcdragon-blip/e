# Fic Reader

A clean, dark Android reader for Archive of Our Own (AO3).

- **Search** AO3 with quick chips (sort, complete only, English, ratings) and a full filter
  sheet: ratings, status, crossovers, language, word count, minimum kudos, fandoms,
  relationships, characters, additional tags, excluded tags, author and title.
  Results load as you scroll. You can also paste an AO3 link or work ID into the search box.
- **Work page** shows rating, status, stats, reading time, summary, tags, series, notes and
  the chapter list. Tap any tag, author or series to browse it.
- **Reader** with five themes (Dark, Dim, Black, Sepia, Light), text size, line spacing,
  serif or sans, margins, spaced or indented paragraphs, justify, tap-to-turn, full screen and
  keep-screen-on. Notes are folded away, and footnote links work. The next chapter is
  fetched ahead of time.
- **Library** of saved works and reading history, with your place in each work saved.
  The refresh button checks saved works for new chapters.
- AO3 links shared to the app, or opened with it, go straight to the work.
- Works limited to logged-in users: Settings > Log in to AO3 opens the real AO3 login
  page in an in-app browser; the reader then uses that session.

Your library is stored on the phone only. Settings > Export library gives you a text
backup you can import again later.

## Build

```
./gradlew assembleRelease
```

The APK is written to `app/build/outputs/apk/release/app-release.apk`, signed with the
debug key so it can be sideloaded. GitHub Actions also builds it on every push that
touches `fic-reader/` (see the "Build Fic Reader APK" workflow; the APK is in the run's
artifacts).

## How it works

The interface is plain HTML/CSS/JS in `app/src/main/assets`, shown in a WebView.
`MainActivity` fetches AO3 pages for it (with your AO3 cookies) and `ao3.js` parses
them. All work text is passed through an allow-list sanitiser before it is shown.
