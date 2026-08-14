# Soneme Recorder

Soneme recorder is a structured voice memo app. It is organized into Series, which represent a recurring thing you want to record and contain the metadata and naming associated with it, and Recordings, which are the invididual recordings associated with a Series. Voice recording is specialized for use when one person speaking is in the same room and the others are coming through a speaker. The major challenge for this recording scenario is getting equivalent voice levels between the different speakers. Primarily a problem of microphone placement and keeping the radio volume adjusted appropriately.

- Uses Android's AudioRecord API AutomaticGainControl feature
- Displays a rolling RMS level
- RMS display has a target line drawn at -12 dBFS and a peak line drawn at -6 dBFS
- Offers a calibration feature--instead of jumping straight into a recording, you can calibrate the microphone placement first by speaking normally and seeing how you're coming through on the meter, then the problem is isolated to just using the volume control on the radio to keep everone else's voices on target.
- Creates a SonemeRecorder folder on the storage medium of your choice, then creates a subfolder for each Series you define. Makes for easy syncing of specific sets of recordings with Soneme Sync.
- Recording playback is handled by the audio player from Soneme Audiobooks. Very little difference in use case here, so the existing player is perfect for it.

## Application overview

On first setup, application asks which storage medium you want to use to save recordings. It checks for an existing folder called SonemeRecorder in the first level of that medium and creates it if it doesn't exist. It then checks if a folder called "Miscellanous" exists inside the SonemeQSO folder and creates it if it doesn't exist.

App starts in Series view.

One series exists by default called Miscellaneous. It is not editable, and is always the last item in Recordings view. It cannot be moved up. Its subfolder is "Miscellaneous".

Recordings are saved as mp3 files with the following metadata:
 - title - individual recording title (defaults to pretty date with trailing parenthesized number if pretty date already exists)
 - artist - Series title
 - year - Recording start timestamp in format YYYY-MM-dd:HH:MM:SS

## Views

### Series

#### Controls

 - D-pad up/down cycles through items
 - Back button returns to launcher
 - D-pad center button selects an item and goes to Recordings view

#### Main content

Header bar reads "Series"

Lists the Series available to record into as items in a list.

Each list item has the Series name (marquee if too long). Below the name, the number of recordings and the date of the last recording.

#### Options menu

 - Delete

   Not available for "Miscellaneous". Opens confirmation with message "Deleting a series will also delete all associated recordings. Be sure any recordings you want to keep are transferred off the device before deleting." Options "Cancel" (default) and "Delete".

 - Move up

   Moves item up in list. Doesn't show if item is top item in list, or if focus is on "Miscellaneous" (bottom item in list)

 - Edit

   Opens Series Edit view for focused item. Doesn't show if focus is on "Miscellaneous"


### Recordings

#### Controls

 - D-pad up/down cycles through items
 - Back button switches to Series view with this series focused
 - D-pad center button opens Player view for focused item

#### Main content

Header bar reads the Series title

Each list item has the track title (marqee if too long), 

#### Options menu

 - Delete
   
   Opens confirmation with message "Delete [name]?" Options "Cancel" (default) and "Delete"

 - (blank)
 
 - Play

   Opens Player view for focused item
