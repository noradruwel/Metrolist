# Jam Session Synchronization Fix

## Problem Statement
The jam session feature had MQTT and basic play/pause functionality working, but songs, queue, and playback position were not properly synchronized across devices. This meant:
- Different devices could play different songs
- Queue was not synced between participants
- Seeking through a song didn't update position on other devices

## Root Causes

### 1. Queue Synchronization Not Implemented
**Location:** `PlayerConnection.kt` lines 311-318 (before fix)
```kotlin
// TODO: Implement queue synchronization with database access
```
The code was receiving queue updates via MQTT but not applying them to the player.

### 2. No Initial State Request
When a new participant joined, they didn't request or receive the current playback state and queue from existing participants.

### 3. Manual Seek Not Broadcast
There was no listener for position discontinuity events, so manual seeking wasn't being broadcast to other participants.

## Solution

### 1. Implemented Queue Synchronization
**File:** `PlayerConnection.kt`

Added full queue synchronization logic:
```kotlin
val songs = database.getSongsByIds(session.queueSongIds)
// Maintain the order from the session
val orderedSongs = session.queueSongIds.mapNotNull { songId ->
    songs.find { it.song.id == songId }
}
if (orderedSongs.isNotEmpty()) {
    val mediaItems = orderedSongs.map { it.toMediaItem() }
    player.setMediaItems(mediaItems, false)
    // Find the current song index and seek to it
    session.currentSongId?.let { currentSongId ->
        val currentIndex = orderedSongs.indexOfFirst { it.song.id == currentSongId }
        if (currentIndex >= 0) {
            player.seekTo(currentIndex, session.currentPosition)
            player.playWhenReady = session.isPlaying
        }
    }
    player.prepare()
}
```

**How it works:**
1. Receives queue song IDs from MQTT
2. Loads songs from local database using `getSongsByIds()`
3. Maintains the exact order from the session
4. Updates player with new queue
5. Restores current song position and play state

### 2. Added Initial State Request/Broadcast
**File:** `JamSessionManager.kt`

Added new message type "REQUEST_STATE":
```kotlin
"REQUEST_STATE" -> {
    // New participant requesting current state
    broadcastCurrentState()
}
```

When joining a session:
```kotlin
// Request current state from other participants
requestCurrentState()
```

When someone joins:
```kotlin
"JOIN" -> {
    // ... existing code ...
    // Broadcast current state to the new participant
    broadcastCurrentState()
}
```

**How it works:**
1. New participant sends "REQUEST_STATE" message on join
2. Existing participants respond with current playback state and queue
3. New participant receives and syncs to match everyone else

### 3. Added Manual Seek Broadcasting
**File:** `PlayerConnection.kt`

Added position discontinuity listener:
```kotlin
override fun onPositionDiscontinuity(
    oldPosition: Player.PositionInfo,
    newPosition: Player.PositionInfo,
    reason: Int,
) {
    // Sync manual seek operations to jam session
    // Only broadcast if this was a manual seek (not triggered by sync)
    if (jamSessionManager.isInSession() && !isSyncing && 
        reason == Player.DISCONTINUITY_REASON_SEEK) {
        jamSessionManager.updatePlaybackState(
            mediaMetadata.value?.id,
            player.currentPosition,
            player.playWhenReady
        )
    }
}
```

**How it works:**
1. Detects when user manually seeks through the song
2. Checks if it's a manual seek (not triggered by network sync)
3. Broadcasts the new position to all participants

## Message Flow Diagram

### Scenario 1: New Participant Joins
```
Device A (existing)          MQTT Broker          Device B (new)
      |                           |                      |
      |                           |    1. JOIN message   |
      |<--------------------------|<---------------------|
      |                           |                      |
      |  2. REQUEST_STATE msg     |                      |
      |<--------------------------|<---------------------|
      |                           |                      |
      |  3. Broadcast current     |                      |
      |     state (UPDATE+QUEUE)  |                      |
      |-------------------------->|--------------------->|
      |                           |                      |
      |                           |  4. Device B syncs   |
      |                           |     to current state |
```

### Scenario 2: User Seeks in Song
```
Device A                     MQTT Broker          Device B
      |                           |                      |
      | 1. User seeks             |                      |
      |-------------------------->|                      |
      |                           |                      |
      |  2. UPDATE message        |                      |
      |     (new position)        |                      |
      |-------------------------->|--------------------->|
      |                           |                      |
      |                           |  3. Device B seeks   |
      |                           |     to new position  |
```

### Scenario 3: Queue Changes
```
Device A                     MQTT Broker          Device B
      |                           |                      |
      | 1. Queue updated          |                      |
      |-------------------------->|                      |
      |                           |                      |
      |  2. QUEUE message         |                      |
      |     (song IDs)            |                      |
      |-------------------------->|--------------------->|
      |                           |                      |
      |                           |  3. Device B loads   |
      |                           |     songs from DB    |
      |                           |     and syncs queue  |
```

## Testing Recommendations

To verify the fix works correctly, test these scenarios:

1. **Queue Synchronization**
   - Device A creates a session with a queue of songs
   - Device B joins the session
   - Verify Device B has the same queue in the same order
   - Add a song to the queue on Device A
   - Verify Device B's queue updates

2. **Song Synchronization**
   - Play a song on Device A
   - Verify the same song plays on Device B
   - Skip to next song on Device B
   - Verify Device A also skips

3. **Position Synchronization**
   - Play a song and seek to middle on Device A
   - Join with Device B
   - Verify Device B starts at the same position
   - Seek forward on Device B
   - Verify Device A also seeks forward

4. **Play/Pause Synchronization**
   - Pause on Device A
   - Verify Device B also pauses
   - Resume on Device B
   - Verify Device A also resumes

## Edge Cases Handled

1. **Songs Not in Database**: If a song ID is in the queue but not in the local database, it's skipped (using `mapNotNull`)
2. **Empty Queue**: Queue sync only happens when `queueSongIds.isNotEmpty()`
3. **Sync Loops**: The `isSyncing` flag prevents re-broadcasting changes that came from the network
4. **Position Drift**: Position only syncs if difference is >2 seconds to avoid constant small adjustments
5. **Order Preservation**: Queue order is maintained using `mapNotNull` with the exact order from session

## Files Modified

1. **app/src/main/kotlin/com/metrolist/music/playback/PlayerConnection.kt** (81 lines changed)
   - Added imports: `Log`, `toMediaItem`
   - Added `onPositionDiscontinuity()` override
   - Implemented queue synchronization in `syncPlaybackState()`

2. **app/src/main/kotlin/com/metrolist/music/utils/JamSessionManager.kt** (39 lines added)
   - Added `requestCurrentState()` method
   - Added `broadcastCurrentState()` method
   - Added "REQUEST_STATE" message handling
   - Modified `joinSession()` to request state
   - Modified "JOIN" handler to broadcast state

## Compatibility

- **MQTT Protocol**: No changes to message format for existing messages
- **Backward Compatibility**: New "REQUEST_STATE" message is optional; old participants will simply ignore it
- **Database**: Uses existing `getSongsByIds()` method; no schema changes needed

## Performance Considerations

1. **Queue Loading**: Songs are loaded in batch using `getSongsByIds()` which is more efficient than individual queries
2. **Hash-based Change Detection**: Queue changes are detected using `hashCode()` to avoid unnecessary updates
3. **Coroutines**: State requests use coroutines to avoid blocking the main thread
4. **Position Sync Threshold**: 2-second threshold prevents constant position adjustments
