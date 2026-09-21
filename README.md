# WhatsApp Transcriber

An Android app that transcribes WhatsApp voice notes on the phone. It runs whisper
large-v3-turbo locally. It sends no audio and no text to a server.

The app is for personal use. It is not on any store. You install it with `adb`.

## What it does

1. It lists the newest WhatsApp audio files as cards. Each card shows the chat name,
   the sender, the message time and the duration.
2. `Load more` adds 20 more cards.
3. A tap on a card without a transcription transcribes the audio. A progress bar
   shows how far whisper is.
4. The text then stays under the card, and the app shows it again on every start.
5. A tap on a card that shows text copies the text to the clipboard.

whisper reads the audio in windows of 30 seconds, so the bar moves once per segment.
A voice note shorter than one window shows little movement before it finishes.

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

The app keeps one SQLite database with the notification log and the transcriptions.
`Wipe stored data` in the menu deletes both tables. Audio files stay untouched.

Both the database and the model live in the private folder of the app. Android deletes
that folder when you uninstall the app. Cloud backup and device transfer are off, so
neither file leaves the phone.

## The model

The app downloads `ggml-large-v3-turbo-q5_0.bin` (about 550 MB) from Hugging Face on
first use, into its private folder. The app loads the model into memory for a
transcription and releases it when the screen stops.

`Delete the model` in the menu frees that space. The next tap downloads the model again.
This download is the only network request the app makes.

## Build

Requirements: Android SDK with platform 35, NDK 27.1.12297006, CMake 3.22, JDK 17.

```sh
git clone --recurse-submodules <this repo>
cd whatsapp_transcriber
echo "sdk.dir=$ANDROID_HOME" > local.properties
./gradlew :app:assembleDebug
```

The APK lands in `app/build/outputs/apk/debug/app-debug.apk`.

## Continuous build

`.github/workflows/build-apk.yml` builds the debug APK on every push to `main`, on every
pull request, and on demand from the Actions tab. It checks out whisper.cpp as a
submodule, installs NDK 27.1.12297006 and CMake 3.22.1, runs `assembleDebug` and
`lintDebug`, then uploads the APK as an artifact named `app-debug-<commit sha>`.

To install a build from the phone, open the run in the Actions tab and download that
artifact.

The workflow builds the same code with the same flags as a local build, so the app
behaves the same. It differs in one way: a runner has no debug keystore, so it makes a
new one for each run. Android refuses to install an APK over an app that carries a
different signature. So a second APK from Actions needs an uninstall first, and an
uninstall deletes the transcriptions and the model. A build from your own machine always
carries the same key, and installs over the previous one.

## Install

```sh
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Then open the app and grant two permissions from its own cards:

1. All files access, for the audio.
2. Notification access, for the chat names.

## Layout

| Path | Contents |
| --- | --- |
| `app/src/main/cpp/whisper_jni.cpp` | JNI bridge to whisper.cpp |
| `app/src/main/java/.../whisper/` | Model download and the whisper context |
| `app/src/main/java/.../audio/` | Opus decoding to mono 16 kHz float |
| `app/src/main/java/.../data/` | File scan, SQLite store, notification listener |
| `app/src/main/java/.../ui/` | Compose screen and view model |
| `third_party/whisper.cpp` | whisper.cpp as a git submodule |
