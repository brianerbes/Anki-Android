# Hands-free Japanese review

## What it does

When enabled in **Settings → Advanced → Hands-free Japanese review**, AnkiDroid reads the front of the card using an installed *offline Spanish text-to-speech voice*, records the spoken answer, transcribes Japanese using Vosk on-device, and compares the transcript against accepted answers. A match is graded **Good**. A mismatch reveals and reads the expected answer if an offline Japanese TTS voice exists, then grades **Again**. If the prompt cannot be spoken, no answer can be found, recognition errors, or the time limit expires without a completed transcript, the card is not automatically graded. If Vosk finalized a speech segment before the listening timeout, that segment may still be checked.

This feature is experimental. Disable it to return to normal manual review at any time.

## Offline behavior and first-time setup

- Speech recognition and comparison run locally. No speech audio or transcription is sent to a server; no LLM is used.
- On first use, the app downloads the approximately 50 MB `vosk-model-small-ja-0.22` model from the official Vosk model host into app-private storage. That one-time download requires an internet connection. After it is installed, airplane mode can be used for review.
- Prompt and correction speech require local Spanish and Japanese Text-to-Speech voices. Android's TTS voice list is checked for voices that do not require a network connection. If they are absent, install offline voice data in Android's Text-to-Speech settings.
- The `RECORD_AUDIO` permission is requested when enabling the feature.

## Accepted answer fields

The recognizer expects spoken Japanese and transcribes it to Japanese text (usually kana and/or kanji). It does **not** convert Latin romaji such as `ikimasu` into Japanese text as a language lesson; the speech recognition itself must recognize the spoken word and output Japanese script.

The reviewer first checks a note field named `VoiceAccepted` or `VoiceAnswers`. Separate accepted responses with a vertical bar, semicolon, or newline. Example:

```
行きます|いきます
```

If neither explicit field exists, it falls back to non-empty note fields containing Japanese characters, excluding a field that exactly matches the spoken prompt. This works best with ordinary vocabulary notes where the front contains a Spanish prompt and another field contains the Japanese answer. For cards with complex templates, multiple Japanese fields, or sentences, add an explicit `VoiceAccepted` field to remove ambiguity. Values are compared after Unicode normalization and removal of whitespace and punctuation; matching is exact after this normalization, not fuzzy.

## Build

Open the repository in Android Studio and build the `fullDebug` variant. Vosk Android and JNA are fetched from Maven Central when building. The Japanese model itself is downloaded separately on first use. Install the debug APK on your Android device and enable hands-free review in Settings → Advanced.

## Limitations to validate on the device

- Speech recognition of isolated Japanese words can be wrong, especially in noise or with short homophones.
- A transcript mismatch is graded Again; it is not a pronunciation assessment.
- The model needs roughly a few hundred megabytes of RAM while loaded, in addition to local storage for the extracted model.
- The first implementation is an experimental review-mode integration and has not yet been tested on a physical device.
