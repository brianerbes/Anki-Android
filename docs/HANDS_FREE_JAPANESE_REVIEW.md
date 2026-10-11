# Hands-free Japanese review

## What it does

When enabled in **Settings → Advanced → Hands-free Japanese review**, AnkiDroid reads the front of the card using an installed *offline Spanish text-to-speech voice*, records the spoken answer, transcribes Japanese using Vosk on-device, and compares the transcript against accepted answers. A match triggers a local Spanish “Correcto” confirmation and is graded **Good**. A mismatch reveals the expected answer and, when an offline Japanese TTS voice is available, reads it aloud before grading **Again**. If correction speech is missing or fails, the answer remains displayed for manual grading rather than advancing silently. If the prompt cannot be spoken, no answer can be found, recognition errors, or the time limit expires without a completed transcript, the card is not automatically graded. If Vosk finalized a speech segment before the listening timeout, that segment may still be checked.

This feature is experimental. Disable it to return to normal manual review at any time.

## Offline behavior and first-time setup

- Speech recognition and comparison run locally. No speech audio or transcription is sent to a server; no LLM is used.
- On first use, the app downloads the approximately 50 MB `vosk-model-small-ja-0.22` model from the official Vosk model host into app-private storage. That one-time download requires an internet connection. After it is installed, airplane mode can be used for review.
- Prompt speech requires a local Spanish Text-to-Speech voice. Spoken correction feedback requires a local Japanese voice. Android's TTS voice list is checked for voices that do not require a network connection. If a voice is missing, install its offline voice data in Android's Text-to-Speech settings. A missing correction voice leaves the card open for manual grading instead of auto-advancing silently.
- The `RECORD_AUDIO` permission is requested when enabling the feature.

## Accepted answer fields

The recognizer transcribes spoken Japanese to Japanese text, usually kana and/or kanji. Matching normalizes katakana, hiragana, kanji readings, and Hepburn-style romaji locally using a Japanese dictionary/transliterator. A card whose answer field contains only `ikimasu` can therefore match a recognized `いきます` or `行きます`, subject to the speech recognizer's accuracy. This is text-reading normalization, not an LLM or a pronunciation-quality assessment.

The reviewer first checks a note field named `VoiceAccepted` or `VoiceAnswers`. Separate accepted responses with a vertical bar, semicolon, or newline. Example:

```
行きます|いきます|ikimasu
```

If neither explicit field exists, it first looks for non-empty note fields containing Japanese characters, excluding a field that exactly matches the spoken prompt. If the note has no kana/kanji fields, it falls back to the first non-prompt field containing a simple Latin word, which supports common romaji-only decks. For cards with complex templates, multiple Japanese fields, or sentences, add an explicit `VoiceAccepted` field to remove ambiguity. Variants are matched by normalized text/readings, not fuzzy semantic grading.

## Build

Open the repository in Android Studio and build the `fullDebug` variant. Vosk Android, JNA, and Kuromoji's Japanese reading dictionary are fetched from Maven Central when building. The Japanese model itself is downloaded separately on first use. Install the debug APK on your Android device and enable hands-free review in Settings → Advanced.

## Limitations to validate on the device

- Speech recognition of isolated Japanese words can be wrong, especially in noise or with short homophones.
- A transcript mismatch is graded Again; it is not a pronunciation assessment.
- The Vosk model and local Japanese reading dictionary both stay in memory during review, so allow several hundred megabytes of available RAM and sufficient storage for the downloaded archive plus extracted model.
- The first implementation is an experimental review-mode integration and has not yet been tested on a physical device.
