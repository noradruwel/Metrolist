# MQTT Implementation in Metrolist

## Overview
Metrolist uses MQTT (Message Queuing Telemetry Transport) for the Jam Session feature, which allows users to sync playback state across multiple devices in real-time.

## Implementation Details

### Library Used
- **Eclipse Paho MQTT Java Client** (`org.eclipse.paho:org.eclipse.paho.client.mqttv3:1.2.5`)
  - This is the standard Java MQTT client library
  - Works on Android without requiring the Android service component
  - Handles MQTT protocol communication directly

### Why Not Using Android Service?
The `org.eclipse.paho.android.service` dependency was removed because:
1. It's not available at version 1.2.5 in Maven repositories
2. The code doesn't use any Android-specific service components
3. The standard Java client (`mqttv3`) is sufficient for our use case
4. We use `MemoryPersistence` which works fine without the Android service

### Code Location
- **Main Implementation**: `app/src/main/kotlin/com/metrolist/music/utils/JamSessionManager.kt`

### Key Components Used
```kotlin
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence
```

### How It Works
1. **Session Creation/Joining**: Users can create or join a jam session with a 6-digit code
2. **MQTT Broker**: Connects to a public MQTT broker (default: `tcp://broker.hivemq.com:1883`)
3. **Topics**: Each session has its own MQTT topic (`metrolist/jam/{sessionCode}`)
4. **Message Types**:
   - `PRESENCE`: Host announces their presence
   - `JOIN`: Participant joins the session
   - `UPDATE`: Playback state updates (song, position, play/pause)
   - `QUEUE`: Queue updates
5. **Synchronization**: All participants subscribe to the session topic and receive updates in real-time

### Build Configuration
The dependency is declared in:
- `gradle/libs.versions.toml`: Version definition (`mqttClient = "1.2.5"`)
- `app/build.gradle.kts`: Implementation dependency (`implementation(libs.mqtt.client)`)

### Testing
The MQTT implementation can be tested by:
1. Building the app successfully
2. Creating a jam session
3. Joining from another device
4. Verifying playback sync across devices

## Research on MQTT in Kotlin/Android

### Best Practices
1. **Use Standard Java Client**: The `org.eclipse.paho.client.mqttv3` library works perfectly on Android
2. **Memory Persistence**: Use `MemoryPersistence()` for lightweight, in-memory message storage
3. **Connection Options**: Configure timeout and keep-alive intervals appropriately
4. **Coroutines**: Use Kotlin coroutines for async operations (as implemented in `JamSessionManager`)
5. **Error Handling**: Implement proper error handling for connection issues

### Why This Implementation is Correct
- ✅ Uses stable, well-maintained library (Eclipse Paho)
- ✅ No dependency on deprecated Android service
- ✅ Lightweight and efficient
- ✅ Works with Kotlin coroutines
- ✅ Proper connection lifecycle management
- ✅ Uses QoS level 1 for reliable message delivery

### Alternative Approaches Considered
1. **Android Service Approach**: Requires `org.eclipse.paho.android.service` - not used because:
   - Adds unnecessary complexity
   - The service component is mainly for background persistence
   - Not needed for our in-app real-time sync use case

2. **Other MQTT Libraries**: 
   - HiveMQ Client: More modern but larger
   - Moquette: Embedded broker, not needed
   - Eclipse Paho is the industry standard and proven

## Build Success
The build will now succeed because:
1. The non-existent `mqtt-android-service` dependency has been removed
2. Only the standard `mqtt-client` library is required
3. The implementation uses only the standard MQTT v3 client classes
4. No code changes were needed - the implementation was already correct

## References
- [Eclipse Paho MQTT Client](https://github.com/eclipse/paho.mqtt.java)
- [MQTT Protocol](http://mqtt.org/)
- [HiveMQ Public Broker](https://www.hivemq.com/mqtt/public-mqtt-broker/)
