# Soneme Recorder

Soneme recorder is a structured voice memo app. It is organized into Series, which represent a recurring thing you want to record and contain the metadata and naming associated with it, and Recordings, which are the invididual recordings associated with a Series. Voice recording is specialized for use when one person speaking is in the same room and the others are coming through a radio speaker. The major challenge for this recording scenario is getting equivalent voice levels between the different speakers. Primarily a problem of microphone placement and keeping the radio volume adjusted appropriately.

- Uses Android's AudioRecord API AutomaticGainControl feature if available, but records without it if not
- Displays a rolling RMS level
- RMS display has a target line drawn at -12 dBFS and a peak line drawn at -6 dBFS
- Offers a calibration feature--instead of jumping straight into a recording, you can calibrate the microphone placement first by speaking normally and seeing how you're coming through on the meter, then the problem is isolated to just using the volume control on the radio to keep everone else's voices on target.
- Creates a SonemeRecorder folder on the storage medium of your choice, then creates a subfolder for each Series you define. Makes for easy syncing of specific sets of recordings with Soneme Sync.
- Recording playback is handled by the audio player from Soneme Audiobooks. Very little difference in use case here, so the existing player is perfect for it.

## Application overview

On first setup, application asks user to select or create a SonemeRecorder folder. If the user knows they already have one, they may select it from their storage. If they are starting fresh, they should create the folder. Once the app has a SonemeRecorder folder to use, persist the tree URI, then check if a folder called "Miscellaneous" exists inside the SonemeRecorder folder and create it if it doesn't exist.

On startup, app scans SonemeRecorder folder and subfolders and checks for any files starting with "TEMP". For each, display a message "Interrupted recording from [date/time] found in [series]. Please enter a title." and a title box with same limitations and validation as the Recorder view's title box. Options menu buttons Discard (blank) Save. Discard deletes the TEMP file and moves on, Save performs the normal finishing process on the TEMP file of creating the ID3v2.4 header, copying the already-encoded MP3 frames after it from the TEMP file, and saving with the normal filename for a finished recording.

App then lands on Series view and waits for interaction.

Items in Series view treat the filesystem as the source of truth. The items in series view are the folders in the SonemeRecorder folder.

One series exists by default called Miscellaneous. It is not editable, and is always the last item in Series view. It cannot be moved up. Its subfolder is "Miscellaneous".

If the "Miscellaneous" folder does not exist, Soneme Recorder creates it on startup.

Recording uses a JNI wrapper around LAME. Live encoding, mono 48 kHz, 96 kbps CBR.

Metadata uses an ID3v2.4 header which is created separately. LAME is used only for MP3 encoding and the header is created separately.
Recordings are live-encoded into a temporary MP3 inside the selected series folder with a filename intentionally outside the normal filename grammar: "TEMP _ [series] _ [timestamp].mp3". On recording save, the final real file is created with the normal naming by writing the ID3v2.4 tag first and copying the already-encoded MP3 frames after it, then deleting the temporary file.

Recordings are saved as mp3 files with the following metadata:
 - title - individual recording title followed by " - " and pretty date (e.g. "Test - August 14, 2026")
 - artist - Series title
 - album - Soneme Recorder
 - year (TDRC) - Recording start timestamp in format 2026-08-14T18:58:23

Timestamps are used throughout the application and vary in format, with conversion necessary frequently. Formats in different contexts are as follows:
UI: Friday, August 14, 2026 06:58 PM
filename timestamp: August 14, 2026, 18.58.23
ID3 TDRC: 2026-08-14T18:58:23
ID3 TIT2: [recording title] - August 14, 2026

Filename timestamp is considered authoritative.

## Views

### Series

#### Controls

 - D-pad up/down cycles through items
 - Back button returns to launcher
 - D-pad center button selects an item and goes to Recordings view

#### Main content

Header bar reads "Series"

Lists the Series available to record into as items in a list. These are the subfolders of the SonemeRecorder folder. These are always displayed in alphabetical order.

Each list item has the Series name (marquee if too long). Below the name, the number of recordings and the date of the last recording. Information is assembled directly from the filesystem and by examining dates in the filenames.

#### Options menu

 - Delete

   Not available for "Miscellaneous". Opens confirmation with message "Deleting a series will also delete all associated recordings. Be sure any recordings you want to keep are transferred off the device before deleting." Options "Cancel" (default) and "Delete".

 - Edit

   Not available for "Miscellaneous". Opens Series Edit view for this series.

 - New

   Opens Series Edit view to create a new series.


### Series Edit

#### Controls

#### Main Content

Series title field, to be saved to the "artist" metadata field on new recordings. Contents of this field must be safe to use as a folder name and may not contain the sequence " - ".
Just the one field--very short main content.

Helpful notes on series titles:

Title is used as a folder name.
If series already exists and is saved, change the previous folder name to the new one.
If new series, create a new folder for this series.

Recordings will go into this folder. It's important that it be an actual folder on the storage medium of choice since the files will presumably be transferred to a NAS with Soneme Sync.

Changes only apply to the filenames and metadata of new recordings. The app does not go through previous recordings and edit existing info, so it is possible that this app shows series titles and recording titles that do not reflect the filenames and metadata of the files within a given series. This is expected. To play nicely with syncing, history is not revised.

#### Options Menu

 - Delete

   Opens confirmation with message "Deleting a series will also delete all associated recordings. Be sure any recordings you want to keep are transferred off the device before deleting." Options "Cancel" (default) and "Delete".

 - Cancel

   Returns to Series view with this Series focused

 - Save

   Only appears if user has made a change and all required fields are valid.


### Recordings

#### Controls

 - D-pad up/down cycles through items
 - Back button switches to Series view with this series focused
 - D-pad center button opens Player view for focused item

#### Main content

Header bar reads the Series title

Each list item has the track title (marqee if too long) with the date and time of the recording as subtext, then total track time to the right.

Title and date/time are taken directly from the filenames. Track title is always what's after the last " - " and before the file extension. Date/time is always taken from what's between the two instances of " - ". If a file fails to follow a naming convention that allows a valid value to be pulled from its name, it is omitted from the list. Follow format or don't show up.

Items are always in date order, newest first,  and are not reorderable.

#### Options menu

 - Delete
   
   Opens confirmation with message "Delete [name]?" Options "Cancel" (default) and "Delete"
 
 - Play

   Opens Player view for focused item

 - New

   Opens the Recorder view

### Recorder

#### Controls

- If recording or calibration is not in progress, back returns to Recordings view without saving. Once calibration or recording has begun (including recording pause state), back is disabled. Calibration must be ended or recording must be finished to go back.

#### Main Content

Series title displayed along top in white bar, marquee if too long.
Date displayed as subtext to series title, time started added when record is pressed. Format "Friday, August 14, 2026 06:58PM". To be saved in the recording's TDRC metadata in format 2026-08-14T18:58:23.
To right, elapsed recording time. Does not increment when in Calibrate or Pause, counts up when recording.

Recording widget and quick indicator widget work on RMS measurement windows of RMS over roughly 40-50ms blocks converted to dBFS.

Recording widget is black background, full width, taking up most of the screen. Rolling display of RMS level representing approximately 10 seconds of audio, with current audio level on right hand side of screen, previous measurement windows rolling to left. Bottom of widget is silence/-60 dBFS, top of widget is 0 dBFS. Level data is #4F6F8F.

Two labeled horizontal dotted lines in white run across the widget: Target Level and Target Peak. Target Level is positioned at -12 dBFS. Target Peak is positioned at -6 dBFS. They are on top of the widget and are purely decorative/for reference.

Level quick indicator is a two-layered bar at the bottom of the screen that averages the sample levels seen over the past 5 seconds. The background layer of the bar is light gray, and the foreground layer is a variable color and opacity.
Opacity is controlled by the proportion of measurement windows that were over -40 dBFS, simple true false. If all measurement windows were over, 100% opacity, if none were over, 0% opacity, and if 50% were over, 50% opacity.
Color is determined by the average level of measurement windows that were over -40 dBFS, with any that were at or below being recorded as -40 dBFS. Bar color is in RGB with intermediary values for each channel between 0 and 255 occurring only in certain ranges of average dBFS.
Red:
<= -12 dBFS - 0
\>= -6 dBFS - 255
Green:
<= -18 dBFS - 0
== -12 dBFS - 255
\>= -6 dBFS - 0
Blue:
<= -18 dBFS - 255
\>= -12 dBFS - 0

Time start is the time the actual recording starts, not the time Recorder view is opened or when Calibrate is used.

For screen closed or recorder losing foreground: Make an active real recording a foreground microphone service, so closing the flip or turning off the display doesn't terminate the recording. Not necessary for Calibrate, only for Record. The foregrounding is retained through Pause/Resume. If a real recording service exists, launching/resuming Soneme Recorder always returns directly to its live Recorder screen, not Series. Service owns start time, elapsed duration, pause state, encoder state, and recent RMS windows--the Activity merely reconnects and redraws them. If Calibrate loses the foreground, calibration should simply stop/discard.

#### Options Menu

 - Cancel
  
   Changes to (blank) when calibration or recording is in progress. If calibration or recording is not in progress, pressing Cancel returns to Recordings view without saving.

 - Calibrate/Done, Pause/Resume

   If recording has not yet started, middle options menu control is Calibrate/Done.
   Pressing Calibrate starts a throwaway recording allowing you to see the recording widget active and determine your audio levels. Pressing Done clears the widget, throws away any audio data for this recording, and switches button back to Calibrate.
   If recording has started, middle options menu control is Pause/Resume.
   Pressing Pause temporarily discards the PCM instead of feeding it to the MP3 encoder until Resume is pressed. Recording widget continues to display audio data, but new audio is not recorded. Change middle options menu button to Resume and show a "Not recording" box over the recording widget. Pressing Resume resumes normal recording, changes middle options menu button back to Pause, and removes the "Not recording" box.

 - Record/Finish

   Starts and ends recording. 
   
   On Record:
   - If currently calibrating (Calibrate has been clicked and Done is shown for middle options menu button), end calibration as if Done was pressed.
   - Switch middle options menu button to Pause/Resume

   On Finish:
   - end recording
   - pop up box with Title field. Title field must be safe to use in a filename and may not contain the sequence " - " or a trailing period. If unsafe characters detected or " - " or trailing period are detected, Save option blanks until corrected and a tooltip 'Special characters and " - " disallowed in titles' is shown. Technically not truthful, but helpful enough.
   - change options menu to Discard (blank) Save
   
   on Discard pressed, discard recording and return to Recordings view.
   on Save pressed, if Title has no value use "Untitled" as value, then:
   
   - show saving... popup
   - Create recording's metadata title field as "[title] - [pretty date]"
   - save file to series folder as "[series] - [pretty date/time] - [title].mp3". If file of same name already exists, just overwrite it since if it has the same name then it can't be longer than a second anyway and was a mistake.
   
   on file save complete
   
   - return to and refresh Recordings view with first item focused


### Player

#### Controls

 - Back button goes to Recordings view.
 - D-pad navigates clickable elements
 - 1 button rewinds 10 seconds
 - 2 button starts previous recording in series at 0:00 if previous exists, or last recording in series at 0:00 if currently playing is first in series. Does nothing if only one recording in series.
 - 3 button fast-forwards 10 seconds
 - 4 button rewinds 60 seconds
 - 5 button starts next recording in series at 0:00 if next exists, or first recording in series at 0:00 if currently playing is last in series. Does nothing if only one recording in series.
 - 6 button fast-forwards 60 seconds
 - 7 button rewinds 600 seconds
 - 8 button cycles repeat setting
 - 9 button fast-forwards 600 seconds
 - * button rewinds 3600 seconds
 - 0 button adds 10 minutes to sleep timer
 - \# button fast-forwards 3600 seconds

#### Main Content

Literally just the Soneme Audiobooks player minus the tab, and minus persistence, and minus stored data per track like last sleep timer set.

Data like playback position, sleep timer, repeat behavior, seek intervals, speed, etc. are discarded if player is exited. App doesn't need to remember data for individual files, just start the player as if it's the first time it's seen this file.

Check image at https://github.com/userexec/soneme-audiobooks/raw/master/screenshot-player.png?raw=true for a visual layout of these features.

Sleep timer remaining countdown in format h:m. Click on sleep timer opens sleep menu.
Repeat icon that reflects current setting. Click on repeat icon opens repeat menu with options for Off, Repeat 1, and Repeat All.
Playback speed indicator that reflects current setting. Click on playback speed indicator opens playback speed menu with options for 0.5x, 0.75x, 1x, 1.25x, 1.5x, 1.75x, 2x, 3x, and 4x. 
Wiper indicator of current playing position. When wiper focused, clicking left button rewinds by interval setting, clicking right fast-forwards by interval setting. Holding left or right pauses the audio, repeats their action once per second until hold released, then returns to playing audio in the new position.
Above wiper, time elapsed on left of wiper in format h:mm:ss. Time remaining on right of wiper in format h:mm:ss. Total track time and listening progress in center of wiper in format "1h 40m - 47%".
Play/pause indicator below wiper.
Previous and Next track buttons to jump to recordings in current series. Grayed out if only one recording is in series.
Rewind and Fast-forward indicators with numbers associated with each per settings in Rewind Interval modal and Fast-forward Interval modal. Settings are expressed here as 10s, 1m, 10m, and 1h to save space. Clicking them rewinds or fast-forwards by the selected interval. Holding them opens their respective modal to adjust their interval setting.
Sleep and repeat changes via keypad controls should have matching haptics to Soneme Audiobooks.

### Options menu

 - Controls

   Lists controls by button in order 1,2,3,4,5,6,7,8,9,*,0,# in a modal. Back button exits modal.

 - Play/Pause (contextual if file is playing)

   Plays and pauses the audio

 - Sleep

   Menu with options for Off, 10 minutes, 30 minutes, 1 hour, 2 hours, 3 hours, 4 hours, 8 hours, 12 hours. Timer begins whenever set, audio pauses when timer runs out. Back button exits modal without setting (sleep timer already in progress should not be affected). Default setting Off.
