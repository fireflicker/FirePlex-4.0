FirePlex Notes
Android TV Plex-style player app.
Uses plex.tv/link to connect to Plex.
Adds second login through your web-panel API.
Player/friendly name is checked against your API before access.
Loads Plex libraries, media, metadata, artwork, posters, and backdrops.
Home screen has Recently Added Movies and Recently Added TV.
Recently Added TV was improved to pull newer TV episodes properly.
Left-side category menu added.
Categories can be shown/hidden from Settings.
Supports ExoPlayer and VLC player choice.
VLC added for better codec/container support.
VLC now has custom controls: play/pause, seek bar, skip back/forward.
Subtitle tracks are pulled from Plex when available.
Settings includes player name, preferred player, categories, speed test.
Added VLC settings: pre-buffer, zoom, subtitles, volume, hardware decoder.
Added ExoPlayer settings: pre-buffer, zoom, subtitles, volume.
Saves token, player name, category choices, player choice, and settings.
Added network permissions and hardware acceleration.
Main files changed: MainActivity.kt, PlexRepository.kt, PlexXmlParser.kt, PlexMediaItem.kt.
New files added: AppAuthRepository.kt, PlayerPlaybackSettings.kt.
