package com.metrolist.music.utils

import android.content.Context
import android.util.Log
import androidx.datastore.preferences.core.edit
import com.metrolist.music.constants.JamSessionBrokerUrlKey
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import org.apache.commons.lang3.RandomStringUtils
import org.eclipse.paho.client.mqttv3.IMqttDeliveryToken
import org.eclipse.paho.client.mqttv3.MqttCallback
import org.eclipse.paho.client.mqttv3.MqttClient
import org.eclipse.paho.client.mqttv3.MqttConnectOptions
import org.eclipse.paho.client.mqttv3.MqttMessage
import org.eclipse.paho.client.mqttv3.persist.MemoryPersistence

/**
 * MQTT-based Jam Session Manager
 * Allows creating/joining sessions and syncing playback state via MQTT broker
 * Uses MQTT topics as jam rooms for easy multi-user synchronization
 */
class JamSessionManager(private val context: Context) {
    
    data class JamSession(
        val sessionCode: String,
        val hostName: String,
        val participants: List<String> = emptyList(),
        val currentSongId: String? = null,
        val currentPosition: Long = 0,
        val isPlaying: Boolean = false,
        val queueSongIds: List<String> = emptyList()
    )
    
    private val _currentSession = MutableStateFlow<JamSession?>(null)
    val currentSession: StateFlow<JamSession?> = _currentSession.asStateFlow()
    
    private val _isHost = MutableStateFlow(false)
    val isHost: StateFlow<Boolean> = _isHost.asStateFlow()
    
    private val scope = CoroutineScope(Dispatchers.IO)
    private var mqttClient: MqttClient? = null
    private var currentTopic: String? = null
    private var currentUserName: String? = null
    
    companion object {
        private const val TAG = "JamSessionManager"
        private const val DEFAULT_BROKER_URL = "tcp://broker.hivemq.com:1883" // Public MQTT broker
        private const val TOPIC_PREFIX = "metrolist/jam/"
    }
    
    init {
        // Restore session on initialization if one exists
        scope.launch {
            restoreSession()
        }
    }
    
    /**
     * Get the MQTT broker URL from preferences
     */
    private suspend fun getBrokerUrl(): String {
        return context.dataStore.data
            .map { preferences ->
                preferences[JamSessionBrokerUrlKey] ?: DEFAULT_BROKER_URL
            }
            .first()
    }
    
    /**
     * Get the MQTT broker credentials from preferences
     */
    private suspend fun getBrokerCredentials(): Pair<String?, String?> {
        return context.dataStore.data
            .map { preferences ->
                val username = preferences[com.metrolist.music.constants.JamSessionBrokerUsernameKey]?.takeIf { it.isNotBlank() }
                val password = preferences[com.metrolist.music.constants.JamSessionBrokerPasswordKey]?.takeIf { it.isNotBlank() }
                Pair(username, password)
            }
            .first()
    }
    
    /**
     * Create a new jam session as host
     */
    fun createSession(hostName: String): String {
        val sessionCode = generateSessionCode()
        
        _currentSession.value = JamSession(
            sessionCode = sessionCode,
            hostName = hostName,
            participants = listOf(hostName)
        )
        _isHost.value = true
        currentUserName = hostName
        
        // Persist session state
        scope.launch {
            persistSession(sessionCode, true, hostName)
        }
        
        // Connect to MQTT broker and subscribe to topic
        connectToMqttBroker(sessionCode, hostName)
        
        return sessionCode
    }
    
    /**
     * Join an existing jam session
     */
    fun joinSession(sessionCode: String, userName: String): Boolean {
        try {
            _currentSession.value = JamSession(
                sessionCode = sessionCode.uppercase(),
                hostName = "Finding host...",
                participants = listOf(userName)
            )
            _isHost.value = false
            currentUserName = userName
            
            // Persist session state
            scope.launch {
                persistSession(sessionCode.uppercase(), false, userName)
            }
            
            // Connect to MQTT broker and subscribe to topic
            connectToMqttBroker(sessionCode.uppercase(), userName)
            
            // Request current state from other participants
            requestCurrentState()
            
            return true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to join session", e)
            return false
        }
    }
    
    /**
     * Update current playback state in the session and broadcast to peers
     * Only call this on manual changes (song change, seek, play/pause)
     */
    fun updatePlaybackState(songId: String?, position: Long, isPlaying: Boolean) {
        _currentSession.value?.let { session ->
            val updated = session.copy(
                currentSongId = songId,
                currentPosition = position,
                isPlaying = isPlaying
            )
            _currentSession.value = updated
            
            // Broadcast update to all peers (both host and participants can control)
            broadcastUpdate(songId, position, isPlaying, session.queueSongIds)
        }
    }
    
    /**
     * Update queue and broadcast to peers
     */
    fun updateQueue(queueSongIds: List<String>) {
        _currentSession.value?.let { session ->
            val updated = session.copy(queueSongIds = queueSongIds)
            _currentSession.value = updated
            
            // Broadcast queue to all peers (both host and participants can control)
            broadcastQueue(queueSongIds)
        }
    }
    
    /**
     * Leave the current session
     */
    fun leaveSession() {
        scope.launch {
            try {
                mqttClient?.disconnect()
                mqttClient?.close()
                mqttClient = null
                currentTopic = null
                currentUserName = null
                
                // Clear persisted session
                clearPersistedSession()
            } catch (e: Exception) {
                Log.e(TAG, "Error disconnecting from MQTT", e)
            }
        }
        _currentSession.value = null
        _isHost.value = false
    }
    
    /**
     * Check if currently in a session
     */
    fun isInSession(): Boolean = _currentSession.value != null
    
    /**
     * Connect to MQTT broker and subscribe to session topic
     */
    private fun connectToMqttBroker(sessionCode: String, userName: String) {
        scope.launch {
            try {
                val brokerUrl = getBrokerUrl()
                val (username, password) = getBrokerCredentials()
                currentTopic = "$TOPIC_PREFIX$sessionCode"
                
                val clientId = "metrolist_${userName}_${System.currentTimeMillis()}"
                mqttClient = MqttClient(brokerUrl, clientId, MemoryPersistence())
                
                val options = MqttConnectOptions().apply {
                    isCleanSession = true
                    connectionTimeout = 10
                    keepAliveInterval = 60
                    // Set username and password if provided
                    if (username != null && password != null) {
                        setUserName(username)
                        setPassword(password.toCharArray())
                    }
                }
                
                mqttClient?.setCallback(object : MqttCallback {
                    override fun connectionLost(cause: Throwable?) {
                        Log.e(TAG, "MQTT connection lost", cause)
                    }
                    
                    override fun messageArrived(topic: String?, message: MqttMessage?) {
                        message?.let {
                            val payload = String(it.payload)
                            handleMqttMessage(payload)
                        }
                    }
                    
                    override fun deliveryComplete(token: IMqttDeliveryToken?) {
                        // Message delivered successfully
                    }
                })
                
                mqttClient?.connect(options)
                mqttClient?.subscribe(currentTopic, 1)
                
                // Announce presence
                val presenceMessage = if (_isHost.value) {
                    "PRESENCE|$userName"
                } else {
                    "JOIN|$userName"
                }
                publishMessage(presenceMessage)
                
                Log.d(TAG, "Connected to MQTT broker: $brokerUrl, topic: $currentTopic")
            } catch (e: Exception) {
                Log.e(TAG, "Error connecting to MQTT broker", e)
            }
        }
    }
    
    /**
     * Handle incoming MQTT messages
     */
    private fun handleMqttMessage(message: String) {
        try {
            val parts = message.split("|")
            if (parts.size < 2) return
            
            when (parts[0]) {
                "JOIN" -> {
                    // Someone joined
                    val userName = parts.getOrNull(1) ?: return
                    _currentSession.value?.let { session ->
                        if (!session.participants.contains(userName)) {
                            _currentSession.value = session.copy(
                                participants = session.participants + userName
                            )
                        }
                    }
                    // Broadcast current state to the new participant
                    broadcastCurrentState()
                }
                "UPDATE" -> {
                    // Playback state update from any participant
                    val songId = parts.getOrNull(1)?.takeIf { it != "null" }
                    val position = parts.getOrNull(2)?.toLongOrNull() ?: 0
                    val isPlaying = parts.getOrNull(3)?.toBoolean() ?: false
                    
                    _currentSession.value?.let { session ->
                        _currentSession.value = session.copy(
                            currentSongId = songId,
                            currentPosition = position,
                            isPlaying = isPlaying
                        )
                    }
                }
                "QUEUE" -> {
                    // Queue update from any participant
                    val queueData = parts.getOrNull(1) ?: return
                    val queueIds = if (queueData.isNotEmpty()) {
                        queueData.split(",")
                    } else {
                        emptyList()
                    }
                    
                    _currentSession.value?.let { session ->
                        _currentSession.value = session.copy(queueSongIds = queueIds)
                    }
                }
                "PRESENCE" -> {
                    // Host announcement
                    val hostName = parts.getOrNull(1) ?: return
                    _currentSession.value?.let { session ->
                        if (session.hostName == "Finding host...") {
                            _currentSession.value = session.copy(hostName = hostName)
                        }
                    }
                }
                "REQUEST_STATE" -> {
                    // New participant requesting current state
                    broadcastCurrentState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error handling message", e)
        }
    }
    
    /**
     * Broadcast playback update to peers via MQTT
     */
    private fun broadcastUpdate(songId: String?, position: Long, isPlaying: Boolean, queueIds: List<String>) {
        scope.launch {
            try {
                val message = "UPDATE|$songId|$position|$isPlaying"
                publishMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting update", e)
            }
        }
    }
    
    /**
     * Broadcast queue update to peers via MQTT
     */
    private fun broadcastQueue(queueSongIds: List<String>) {
        scope.launch {
            try {
                val queueData = queueSongIds.joinToString(",")
                val message = "QUEUE|$queueData"
                publishMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error broadcasting queue", e)
            }
        }
    }
    
    /**
     * Publish message to MQTT topic
     */
    private fun publishMessage(message: String) {
        try {
            currentTopic?.let { topic ->
                val mqttMessage = MqttMessage(message.toByteArray()).apply {
                    qos = 1
                    isRetained = false
                }
                mqttClient?.publish(topic, mqttMessage)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error publishing MQTT message", e)
        }
    }
    
    /**
     * Request current state from other participants (called by new joiners)
     */
    private fun requestCurrentState() {
        scope.launch {
            try {
                val message = "REQUEST_STATE|"
                publishMessage(message)
            } catch (e: Exception) {
                Log.e(TAG, "Error requesting state", e)
            }
        }
    }
    
    /**
     * Broadcast current state to all participants (called by existing participants)
     */
    private fun broadcastCurrentState() {
        _currentSession.value?.let { session ->
            // Broadcast current playback state
            if (session.currentSongId != null) {
                broadcastUpdate(session.currentSongId, session.currentPosition, session.isPlaying, session.queueSongIds)
            }
            // Broadcast current queue
            if (session.queueSongIds.isNotEmpty()) {
                broadcastQueue(session.queueSongIds)
            }
        }
    }
    
    /**
     * Generate a 6-digit session code
     */
    private fun generateSessionCode(): String {
        return RandomStringUtils.insecure().next(6, false, true).uppercase()
    }
    
    /**
     * Persist session state to preferences
     */
    private suspend fun persistSession(sessionCode: String, isHost: Boolean, userName: String) {
        context.dataStore.edit { preferences ->
            preferences[com.metrolist.music.constants.JamSessionActiveCodeKey] = sessionCode
            preferences[com.metrolist.music.constants.JamSessionIsHostKey] = isHost
            preferences[com.metrolist.music.constants.JamSessionUserNameKey] = userName
        }
    }
    
    /**
     * Clear persisted session state
     */
    private suspend fun clearPersistedSession() {
        context.dataStore.edit { preferences ->
            preferences.remove(com.metrolist.music.constants.JamSessionActiveCodeKey)
            preferences.remove(com.metrolist.music.constants.JamSessionIsHostKey)
            preferences.remove(com.metrolist.music.constants.JamSessionUserNameKey)
        }
    }
    
    /**
     * Restore session from preferences if exists
     */
    private suspend fun restoreSession() {
        try {
            val sessionCode = context.dataStore.data.map { 
                it[com.metrolist.music.constants.JamSessionActiveCodeKey] 
            }.first()
            val isHost = context.dataStore.data.map { 
                it[com.metrolist.music.constants.JamSessionIsHostKey] ?: false 
            }.first()
            val userName = context.dataStore.data.map { 
                it[com.metrolist.music.constants.JamSessionUserNameKey] 
            }.first()
            
            if (sessionCode != null && userName != null) {
                Log.d(TAG, "Restoring session: $sessionCode")
                _isHost.value = isHost
                currentUserName = userName
                
                if (isHost) {
                    _currentSession.value = JamSession(
                        sessionCode = sessionCode,
                        hostName = userName,
                        participants = listOf(userName)
                    )
                } else {
                    _currentSession.value = JamSession(
                        sessionCode = sessionCode,
                        hostName = "Finding host...",
                        participants = listOf(userName)
                    )
                }
                
                // Reconnect to MQTT broker
                connectToMqttBroker(sessionCode, userName)
                
                if (!isHost) {
                    // Request current state from other participants
                    requestCurrentState()
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error restoring session", e)
        }
    }
}
