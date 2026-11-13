# Android Auto song information on the car display

FT forwards the current Android Auto song title, artist, album, cover art and
playback position to the head unit through CarLife. The head unit decides which
information appears on its own display, instrument cluster or HUD. Support and
layout depend on the car.

Enable **Settings → Sound → Show the song on the car's own display**. This is the
existing setting used by FT's player; it also controls Android Auto song
information and takes effect during the current connection. No notification
access permission is required. Android Auto must provide the song information;
mirroring another app or forwarding its sound alone does not provide metadata.

While Android Auto is playing, its song information takes priority over FT's
player. FT sends the current track again after playback resumes, the car
reconnects or its music subscription restarts. Paused tracks are not published,
to avoid taking over the head unit's radio. Changing to the car's own screen
pauses only video; song information continues while Android Auto plays.

Cover conversion and CarLife writes run outside Android Auto's receive loop.
Updates are coalesced, progress is limited to once per second except backward
seeks, and track changes discard the previous track's cover and position.
CarLife track duration uses seconds; playback position uses milliseconds.
Cover art is converted to a small JPEG; unsupported or oversized images are
omitted without dropping the text information.

## Vehicle check

1. Enable the setting, connect and start music in Android Auto. Check the title
   and artist on the car display and instrument cluster, where supported.
2. Change tracks, including one without a cover. Check that the old title and
   artwork are replaced, then seek backward and check the position if displayed.
3. Pause playback and switch to the car radio. FT should not publish paused
   track information. Resume Android Auto playback and check the current song.
4. Open the car's own screen while Android Auto keeps playing, then change the
   track. Instrument-cluster information should continue updating.
5. Switch the song-information setting off and on, then reconnect to the car
   while a song is already playing. The current song should be sent again.

Unit tests cover protocol decoding, discovery, subscription changes, pause and
resume, reconnect replay, time units and queued updates. Actual cluster support
and cover rendering still require a vehicle test.
