# WhatsApp Transcriber

An Android app that transcribes WhatsApp voice notes on the phone. It runs a whisper
or Whistle model locally. It sends no audio and no text to a server.

The app is for personal use. It is not on any store. You install it with `adb`.

## What it does

1. It lists the newest WhatsApp audio files as cards. Each card shows the chat name,
   the sender, the message time and the duration.
2. `Load more` adds 20 more cards.
3. A tap on a card without a transcription transcribes the audio. The card shows the
   seconds elapsed and, once the model finishes a segment, the percentage done.
4. The text then stays under the card, and the app shows it again on every start.
5. A tap on a card that shows text copies the text to the clipboard.
6. Under the text the card reports how long the transcription took.

The menu sets the spoken language and the model. Italian is the default. With whisper,
`Detect the language` costs a second encoder pass, and on a short note it often picks a
wrong language.

## How it reads the audio

WhatsApp writes its media to `Android/media/com.whatsapp/WhatsApp/Media/`. Android
does not restrict that folder, so any app with the all-files permission reads it.
The app scans two subfolders:

- `WhatsApp Voice Notes` holds the recorded voice messages.
- `WhatsApp Audio` holds audio files that people send or forward.

The app never writes to these folders and never deletes an audio file.

## How it finds the chat name

The audio file name holds only a date and a counter, such as `PTT-20260908-WA0007.opus`.
The link between a file and a chat is in `msgstore.db`, in the private data folder of
WhatsApp. No app reads that file without root.

So the app reads the chat name from the WhatsApp notification instead. A
`NotificationListenerService` stores the chat name, the sender and the time of each
WhatsApp message notification. The app then pairs an audio file with the notification
closest in time.

The method has limits:

- The log starts when you grant notification access. Older audio shows `Unknown chat`.
- Audio that you send yourself has no notification, so it stays unnamed.
- A muted chat posts no notification.
- The pairing uses time, so it can name the wrong chat when two messages arrive together.

## What the app stores

The app keeps one SQLite database with the notification log, the transcriptions and the
time each transcription took.
`Wipe stored data` in the menu deletes both tables. Audio files stay untouched.

Both the database and the model live in the private folder of the app. Android deletes
that folder when you uninstall the app. Cloud backup and device transfer are off, so
neither file leaves the phone.

## The model

The menu offers two models:

| Model | Engine | File | Size |
| --- | --- | --- | --- |
| large-v3-turbo (default) | whisper.cpp | `ggml-large-v3-turbo-q5_0.bin` | 570 MB |
| Whistle | needle | `whistle.cact` | 17 MB |

Whistle transcribes much faster and keeps the phone cool, but makes more errors.

Whistle is a speech model from Cactus Compute. It runs on the CPU in the needle engine.
Of the app's languages it reads Italian and English, and `Detect the language` lets it
pick between its own seven languages. It reads at most 30 s of audio per pass. The app
cuts a longer note into windows of at most 30 s, and ends each window at the quietest
100 ms of its last 5 s, so a cut rarely splits a word.

One benchmark on a Mac (M4 Pro, CPU only, Italian) compares the models on the 10 longest
notes, 48 minutes of audio. large-v3 gives the reference text, so the word error rate (WER)
is the distance from large-v3, not from a human transcript.

| Model | WER vs large-v3 | CPU seconds per minute of audio |
| --- | --- | --- |
| large-v3-turbo q5_0 | 13.3 % | 106 |
| base q5_1 (not in the app) | 45.2 % | 17 |
| Whistle | 38.9 % | 1.5 |

The app downloads the selected model from Hugging Face into its private folder. A switch
to another model deletes the file of the previous model, so only one model takes space.
The download card then offers the new model.

`Delete the model` in the menu frees that space. The next tap downloads the model again.
This download is the only network request the app makes.

The app reads the model into memory when the screen opens. It releases large-v3-turbo
when the screen stops, because that model holds about 600 MB. The needle engine has no
call that frees a model, so Whistle stays in memory until Android stops the app. A tap
made while `Loading the model` shows waits for the load to finish.

## Speed

whisper encodes audio in windows of 30 seconds. For a 3 second note that is 27 seconds
of padding, which costs time and makes the model invent text. The app sizes the window
to the audio, at 1.5 times its length with a floor of 320 positions of 20 ms. On one
3 second note this cut the encoder from 8.4 s to 0.9 s on a laptop CPU, and turned an
Icelandic guess into the right Italian sentence.

The native library is compiled for `armv8.2-a+dotprod+fp16`, so it needs a phone from
about 2018 or later. It runs whisper on every core. The needle engine picks its own threads.

## Build

Requirements: Android SDK with platform 35, NDK 27.1.12297006, CMake 3.22, JDK 17.

```sh
git clone --recurse-submodules <this repo>
cd whatsapp_transcriber
echo "sdk.dir=$ANDROID_HOME" > local.properties
./build.sh
```

`third_party/needle` holds the needle engine as a prebuilt static library, `libneedle.a`
and `needle.h` from the `android-arm64` folder of
[Cactus-Compute/needle3](https://huggingface.co/Cactus-Compute/needle3) at revision
`2ae11323dc000f5e70c49f7403efa6af12ba9e67`, under the Apache-2.0 license. The app
downloads `whistle.cact` from
[Cactus-Compute/whistle](https://huggingface.co/Cactus-Compute/whistle) at revision
`b358ddadd89b7a713b5aa131f23032d3cca1b251`.

Before the Gradle build, `build.sh` lists the functions that `libneedle.a` imports, with
`llvm-nm` from the NDK. It stops the build when the library imports a network, process or
dynamic loading function, such as `socket`, `connect`, `getaddrinfo`, `dlopen` or `fork`.
So the engine cannot send telemetry or other data.

`build.sh` reads `versionName` from `app/build.gradle.kts` and copies the APK to
`builds/whatsapp-transcriber-v<version>.apk`. The `builds` folder is not in git. A build
of the same version replaces the file.

## Continuous build

`.github/workflows/build-apk.yml.disabled` holds a workflow that builds the APK and
publishes it as a release. It is off, and the `.disabled` suffix keeps GitHub from
running it.

A runner has no debug keystore, so it makes a new one on every run. Android compares
the signing key before the version and refuses to install an APK over an app that
carries a different key. So each build from a runner needed an uninstall first, and an
uninstall deletes the transcriptions and the model. Builds from one machine all carry
the same key and install over each other.

To turn the workflow on again, drop the `.disabled` suffix and add a keystore that every
build shares.

## Install

```sh
adb install -r builds/whatsapp-transcriber-v1.2.apk
```

Then open the app and grant two permissions from its own cards:

1. All files access, for the audio.
2. Notification access, for the chat names.

## Layout

| Path | Contents |
| --- | --- |
| `app/src/main/cpp/whisper_jni.cpp` | JNI bridge to whisper.cpp |
| `app/src/main/cpp/needle_jni.cpp` | JNI bridge to the needle engine |
| `app/src/main/java/.../whisper/` | Model download, the whisper context and Whistle |
| `app/src/main/java/.../audio/` | Opus decoding to mono 16 kHz float |
| `app/src/main/java/.../data/` | File scan, SQLite store, notification listener, settings |
| `app/src/main/java/.../ui/` | Compose screen and view model |
| `third_party/whisper.cpp` | whisper.cpp as a git submodule |
| `third_party/needle` | Prebuilt needle engine for arm64 Android, with its license |
