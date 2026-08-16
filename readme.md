# Soneme Recorder

![Soneme Recorder Icon](https://github.com/userexec/soneme-recorder/blob/master/soneme_recorder_icon.png?raw=true)

Soneme Recorder is a small, keypad-friendly Android voice recorder built around structured, repeatable recordings rather than a giant undifferentiated pile of voice memos.

Recordings are organized into **Series**. A Series is a recurring thing you record—such as a radio net, meeting, interview series, class, or other regular event. Each Series is an ordinary folder on the phone, and each finished recording is an ordinary MP3 inside it.

Recorder was designed particularly for situations where **one speaker is physically in the room and the other speakers are coming through a radio or other loudspeaker**. In that situation, getting reasonably consistent voice levels is mostly a matter of microphone placement and adjusting the speaker volume. Recorder provides a live RMS history, target guides, a large quick level indicator (think of it as an at-a-glance "Magic Eye" display), and a disposable calibration mode to make that much easier.

It is designed specifically for the **Sonim XP3plus XP3900**. There are no touch controls for the main interface. A normal Android phone is not a target and probably will not have the Sonim softkeys Recorder expects.

![Series view](https://github.com/userexec/soneme-recorder/blob/master/screenshot-series.png?raw=true)  ![Recordings view](https://github.com/userexec/soneme-recorder/blob/master/screenshot-recordings.png?raw=true)  ![Recording example](https://github.com/userexec/soneme-recorder/blob/master/screenshot-recording-1.png?raw=true)  ![Recording example](https://github.com/userexec/soneme-recorder/blob/master/screenshot-recording-2.png?raw=true)  ![Recording example](https://github.com/userexec/soneme-recorder/blob/master/screenshot-recording-3.png?raw=true)  ![Player view](https://github.com/userexec/soneme-recorder/blob/master/screenshot-player.png?raw=true)

## Features

* Structured recordings organized into Series
* Live 48 kHz mono MP3 recording at 96 kbps CBR
* Rolling RMS level history
* Large five-second quick level indicator for at-a-glance monitoring
* Target Level and Target Peak guides
* Calibration mode that meters audio without creating a recording
* Pause and Resume while continuing to monitor live levels
* Current clock time and recorded duration visible while recording
* Background recording with the screen off or flip closed
* Crash/interruption recovery for recordings that were not finished normally
* Ordinary folders and MP3 files rather than a proprietary recording library
* ID3 metadata written into finished recordings
* Built-in playback based on the Soneme Audiobooks player
* Playback speed, repeat modes, sleep timer, and adjustable seek intervals
* Hardware keypad playback shortcuts
* Headset and Bluetooth media controls
* Sonim softkey integration
* No accounts, analytics, advertising, subscriptions, cloud services, or runtime network access

## Tested Device

Soneme Recorder has been developed and tested on:

* Sonim XP3plus XP3900 — Android 11 Go

The interface is designed for the XP3900's 240x320 non-touch display, D-pad, numeric keypad, and native three-position Sonim softkey bar.

## Installing

Soneme Recorder is distributed as a normal Android APK.

Copy the APK to the device and install it, or install from a connected computer with ADB:

```sh
adb install soneme-recorder.apk
```

If updating an existing release signed with the same release key:

```sh
adb install -r soneme-recorder.apk
```

Android may require permission to install apps from unknown sources when installing directly on the phone.

## First Setup

Recorder stores its audio in a normal folder named:

```text
SonemeRecorder
```

On first launch, Recorder explains that no recording folder is configured and asks where it should live. Choose **Set up** to open Android's system folder picker.

If you already have a `SonemeRecorder` folder, you can select either that folder itself or the folder containing it. If you are starting fresh, select the storage location where you want Recorder to create it. Internal storage and removable SD-card storage both work.

Recorder remembers access to that location afterward. If the configured storage later becomes unavailable—for example, because the SD card has been removed—Recorder will ask you to choose a recording folder again or exit.

Recorder automatically creates a reserved **Miscellaneous** Series if one does not already exist.

### XP3900 folder-picker quirk

On a freshly installed copy, Android's system folder picker may initially display a blank Sonim softkey bar. Pressing the D-pad once causes the normal **Cancel** and **Select** labels to appear. The picker itself is still functional before the labels appear.

This behavior occurs in the XP3900's system DocumentsUI rather than Recorder and does not affect Recorder's own softkeys after setup.

## How Recorder Is Organized

Recorder deliberately treats the filesystem as its recording library.

A typical recording folder looks like:

```text
SonemeRecorder/
├── Amateur Radio Net/
│   ├── Amateur Radio Net - August 16, 2026, 08.00.00 - Weekly Net.mp3
│   └── Amateur Radio Net - August 9, 2026, 08.00.00 - Weekly Net.mp3
├── Meetings/
│   └── Meetings - August 14, 2026, 13.30.00 - Planning Meeting.mp3
└── Miscellaneous/
```

There is no hidden database containing the recordings themselves. Series are folders, and recordings are MP3 files.

This also makes Series easy to copy or synchronize individually with something like Soneme Sync.

## Series

The first screen lists all Series alphabetically, with **Miscellaneous** always pinned last.

Each Series shows:

* its name,
* the number of recognized recordings in the folder,
* and the date of its newest recording.

Press the D-pad center button to open a Series and view its recordings.

Use **New** to create a Series. The Series name becomes its folder name and is also written as the Artist field on new recordings.

Use **Edit** to rename an existing Series. Renaming a Series renames the folder only. Recorder deliberately does **not** go back and rewrite the filenames or metadata of old recordings. Historical files remain historical files.

### Deleting a Series

Deleting a Series deletes the **entire Series folder and everything inside it**, including files Recorder does not recognize.

If something in that folder matters, move or copy it somewhere else before confirming the deletion.

The reserved Miscellaneous Series cannot be renamed or deleted.

## Recordings

Opening a Series shows its recordings newest first.

Each row displays the recording title, recording date/time, and duration. Selecting a recording opens the Player.

Choose **New** to open the Recorder for that Series.

Recorder recognizes finished recordings by its filename format and by verifying that Android can open them as audio. Files that do not match the expected format are simply ignored; Recorder does not delete them.

The normal filename format is:

```text
[Series] - [date and time] - [title].mp3
```

For example:

```text
Amateur Radio Net - August 16, 2026, 08.00.00 - Weekly Net.mp3
```

Because filenames are part of Recorder's library structure, manually renaming finished recordings to something completely different may make them disappear from the app's lists even though the files remain on storage.

## Calibrating Levels

Before making a real recording, choose **Calibrate**.

Calibration runs the same microphone, optional Android automatic gain control, and level-metering path used during a recording, but it does **not** create an MP3 file. Choose **Done** when finished.

The intended workflow for recording a person in the room together with voices from a radio or speaker is:

1. Put the phone where it will remain during the recording.
2. Start Calibrate.
3. Speak normally and use the level display to settle on a sensible microphone position.
4. Once your own voice is landing around the target level, leave the phone alone.
5. Adjust the radio or speaker volume until remote voices land in roughly the same area.
6. Choose Done, then start the real recording.

Android's `AutomaticGainControl` is used when the XP3900 makes it available and allows it to be enabled. If it is unavailable, Recorder simply continues without it.

Calibration is disposable. If Recorder loses the foreground while calibrating, calibration ends rather than continuing in the background.

## Understanding the Level Display

The large black graph shows roughly the last ten seconds of RMS audio level. New audio enters from the right and rolls toward the left. The blue area under the trace makes the recent level history easy to read at a glance.

Two dotted reference lines are drawn over the graph:

* **Target Level: -20 dBFS** — a useful neighborhood for normal voice level.
* **Target Peak: -8 dBFS** — a warning reference for louder peaks and reduced headroom.

These are guides, not hard limits. Speech is variable. The goal is a healthy average level with enough room above it for louder syllables and unexpected peaks.

Beneath the graph is the large quick level indicator. It summarizes roughly the last five seconds:

* **Blue** means the recent average is quiet.
* **Green** centers around the -20 dBFS target.
* **Yellow/orange** is the transition toward an increasingly hot signal.
* **Red** indicates the recent level has reached the upper warning range, becoming fully red at about -8 dBFS and above.

The indicator's opacity is independent of its color. A mostly transparent bar means relatively little of the last five seconds contained meaningful audio; a solid bar means sound was present consistently. Quiet windows remain part of the level average deliberately, so the color fades rather than jumping instantly whenever someone stops speaking.

## Making a Recording

Choose **Record** to begin.

The first time you use Calibrate or Record, Android will ask for microphone permission. Recorder cannot record without it.

While recording, the bottom status bar shows:

* **Current time** — the phone's local clock, including seconds.
* **Recorded** — the actual amount of audio recorded so far.

Recorded time does not advance while paused.

### Pause and Resume

Choose **Pause** to stop adding audio to the MP3 without stopping the session. The level display continues to monitor the microphone, and Recorder shows **Not recording** over the meter.

Choose **Resume** to continue writing audio to the same recording.

### Closing the phone

A real recording runs as an Android foreground microphone service. Closing the flip, turning off the display, or temporarily leaving the Activity does **not** stop the recording.

Recorder shows an Android foreground notification while a recording is active. Reopening Recorder reconnects to the same live recording, including its elapsed time, pause state, and recent meter history.

### Finishing and saving

Choose **Finish** when the recording is over.

Recorder asks for a title. Leaving the title blank saves it as **Untitled**.

Choose **Save** to finish the MP3 and return to the Recordings list, or **Discard** to throw the recording away.

The recording start time is the moment **Record** was pressed—not when the Recorder screen was opened and not when calibration began.

## Recording Format and Metadata

Finished recordings are ordinary MP3 files:

* 48,000 Hz
* mono
* 96 kbps CBR
* ID3v2.4 metadata

Recorder writes metadata including:

* **Title:** recording title plus recording date
* **Artist:** Series name
* **Album:** Soneme Recorder
* **Recording time:** the recording start timestamp

The MP3s can be copied off the phone and played by normal audio software without Soneme Recorder.

## Interrupted Recording Recovery

Recorder is intentionally conservative about losing audio.

While a recording is active, encoded MP3 data is written to a temporary file in the Series folder. Recorder does not depend on keeping the entire recording in memory until Finish is pressed.

If the app, process, or phone is interrupted before a recording can be finished normally, reopen Recorder with the same storage available. Recorder scans for interrupted recordings and, when usable MP3 frames exist, asks you to enter a title or discard the recovered audio.

If the final MP3 frame was cut off by the interruption, Recorder keeps the complete frames before it and ignores the incomplete tail.

Saving is also staged so the original temporary recording is not deleted until the finished MP3 has been completely written and committed. A failed save therefore normally leaves the temporary recording available for another recovery attempt.

Calibration is not recoverable because calibration intentionally creates no recording file.

## Player

Recorder's Player is closely based on Soneme Audiobooks and is intended to be equally usable from the keypad.

The Player provides:

* Sleep timer
* Repeat Off, Repeat 1, and Repeat All
* Playback speeds from 0.5x through 4x
* Elapsed, remaining, total, and percentage progress
* Seek wiper
* Play/Pause
* Previous/Next recording
* Adjustable Rewind/Fast-forward intervals

Closing the flip or turning off the display does not exit the Player. Playback continues and reopening the phone reconnects to the same Player session.

Pressing **Back** to return to Recordings is different: it ends that Player session. Playback position, speed, repeat mode, sleep timer, and seek interval changes are intentionally not saved for the next time the recording is opened.

Previous and Next follow the same newest-first order shown in the Recordings list and wrap around at either end when more than one recording exists.

## Player Keypad Shortcuts

| Key | Action |
| --- | --- |
| `1` | Rewind 10 seconds |
| `2` | Previous recording |
| `3` | Forward 10 seconds |
| `4` | Rewind 1 minute |
| `5` | Next recording |
| `6` | Forward 1 minute |
| `7` | Rewind 10 minutes |
| `8` | Cycle Repeat mode |
| `9` | Forward 10 minutes |
| `*` | Rewind 1 hour |
| `0` | Add 10 minutes to the sleep timer |
| `#` | Forward 1 hour |

The Player softkeys are:

* **Left:** Controls
* **Center:** Play/Pause
* **Right:** Sleep

Pressing `8` uses the same repeat-mode vibration patterns as Soneme Audiobooks, and pressing `0` gives a short vibration when ten minutes are added to the sleep timer.

The on-screen Rewind and Fast-forward buttons can be held to change their interval between 10 seconds, 1 minute, 10 minutes, and 1 hour.

## Storage and Privacy

Soneme Recorder is intentionally local-only.

It does not require:

* an account,
* internet access while running,
* cloud storage,
* Google Play Services,
* analytics,
* advertising,
* or a subscription.

The application does not request Android's Internet permission.

Microphone access is used for Calibrate and Record. Finished recordings are stored only in the `SonemeRecorder` location you selected. Recorder's private application storage holds only small configuration/state information such as the persisted storage-folder reference.

## Building

Soneme Recorder is a Gradle Android project with a small native LAME MP3 encoder component.

The build environment requires:

* JDK 17 or newer
* Android SDK platform 34 and build tools
* Android NDK 27.0.12077973
* CMake 3.22.1
* Git

Build a debug APK with:

```sh
./gradlew assembleDebug
```

For a configured signed release build:

```sh
export SONEME_KEYSTORE=/path/to/keystore.jks
export SONEME_STORE_PASSWORD='...'
export SONEME_KEY_PASSWORD='...'
./gradlew assembleRelease
```

The configured release key alias is `soneme`.

The resulting APK is written beneath:

```text
app/build/outputs/apk/
```

The first build needs internet access so Gradle can obtain its dependencies and the native build can fetch the pinned Android-adjusted LAME 3.100 source tree. The application itself does not need internet access at runtime.

See `BUILDING.md` for build-environment notes and `THIRD_PARTY_NOTICES.md` for the LAME licensing/source information.
