import androidx.compose.material3.SmallTopAppBar

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    permissionsGranted: Boolean,
    onFinish: () -> Unit
) {
    val context = LocalContext.current
    val goProManager = GoProManager.getInstance(context)
    val scope = rememberCoroutineScope()

    val connectionState by goProManager.connectionState.collectAsState()
    val scannedDevices by goProManager.scannedDevices.collectAsState()
    val pairedDevices by goProManager.pairedDevices.collectAsState()
    val isRecording by goProManager.isRecording.collectAsState()
    val recordingDuration by goProManager.recordingDuration.collectAsState()
    val batteryLevel by goProManager.batteryLevel.collectAsState()
    val remainingTime by goProManager.remainingVideoTime.collectAsState()
    val cameraMode by goProManager.cameraMode.collectAsState()

    var isProcessing by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) } // State for dropdown menu
    var isAutoConnecting by remember { mutableStateOf(false) } // New state for auto-connecting
    var showFeedbackScreen by remember { mutableStateOf(false) } // New state for FeedbackScreen

    // Auto-connect on startup only
    LaunchedEffect(Unit) {
        if (pairedDevices.isNotEmpty()) {
            isAutoConnecting = true
            goProManager.tryAutoConnect()
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            goProManager.stopScan()
            goProManager.disconnect()
        }
    }

    Scaffold(
        topBar = {
            val currentState = connectionState // Introduce local variable
            SmallTopAppBar(
                                title = {
                                    Text(
                                        text = if (showFeedbackScreen) {
                                            "Feedback" // Title for feedback screen
                                        } else if (currentState is ConnectionState.Connected) {
                                            "Connected to ${currentState.deviceName ?: "Unknown Device"}"
                                        } else {
                                            "Karoo Camera Control"
                                        },
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                },
                                actions = {
                                    if (!showFeedbackScreen) { // Always show menu icon when not on feedback screen
                                        IconButton(onClick = { showMenu = true }) {
                                            Icon(Icons.Default.MoreVert, contentDescription = "More options")
                                        }
                                        DropdownMenu(
                                            expanded = showMenu,
                                            onDismissRequest = { showMenu = false }
                                        ) {
                                            DropdownMenuItem(
                                                text = { Text("Disconnect") },
                                                onClick = {
                                                    goProManager.disconnect()
                                                    showMenu = false
                                                },
                                                enabled = currentState is ConnectionState.Connected // Only enabled when connected
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Unpair / Forget") },
                                                onClick = {
                                                    goProManager.forgetConnectedDevice()
                                                    showMenu = false
                                                },
                                                enabled = currentState is ConnectionState.Connected // Only enabled when connected
                                            )
                                            DropdownMenuItem(
                                                text = { Text("Leave Feedback") },
                                                onClick = {
                                                    showFeedbackScreen = true
                                                    showMenu = false
                                                }
                                            )
                                        }
                                    }
                                },
                                colors = TopAppBarDefaults.smallTopAppBarColors(
                                    containerColor = MaterialTheme.colorScheme.primary,
                                    titleContentColor = MaterialTheme.colorScheme.onPrimary,
                                    actionIconContentColor = MaterialTheme.colorScheme.onPrimary
                                )
                            )        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
                .background(MaterialTheme.colorScheme.background)
        ) {
            val currentState = connectionState // Introduce local variable

            if (showFeedbackScreen) {
                FeedbackScreen(onFinish = { showFeedbackScreen = false })
            } else {
                when (val state = currentState) {
                    is ConnectionState.Connected -> {
                        isAutoConnecting = false
                        ConnectedScreen(
                            deviceName = state.deviceName,
                            isRecording = isRecording,
                            isProcessing = isProcessing,
                            recordingDuration = recordingDuration,
                            batteryLevel = batteryLevel,
                            remainingTime = remainingTime,
                            cameraMode = cameraMode,
                            onToggleRecording = {
                                if (isProcessing) return@ConnectedScreen
                                isProcessing = true
                                scope.launch {
                                        try {
                                            if (isRecording) {
                                                goProManager.stopRecording()
                                            } else {
                                                goProManager.startRecording()
                                            }
                                        } finally {
                                            isProcessing = false
                                        }
                                    }
                                },
                            onSetMode = { mode ->
                                if (isProcessing) return@ConnectedScreen
                                isProcessing = true
                                scope.launch {
                                    try {
                                        goProManager.setMode(mode)
                                    } finally {
                                        isProcessing = false
                                    }
                                }
                            },
                            onDisconnect = { goProManager.disconnect() },
                            onForget = { goProManager.forgetConnectedDevice() },
                            onFinish = { onFinish() },
                            isConnecting = false, // Not connecting, so false
                            onCancelConnecting = {} // Not applicable here, so empty lambda
                        )
                    }
                    is ConnectionState.Connecting -> {
                        ConnectedScreen(
                            deviceName = state.deviceName,
                            isRecording = isRecording,
                            isProcessing = isProcessing,
                            recordingDuration = recordingDuration,
                            batteryLevel = batteryLevel,
                            remainingTime = remainingTime,
                            cameraMode = cameraMode,
                            onToggleRecording = {}, // Disabled when connecting
                            onSetMode = {}, // Disabled when connecting
                            onDisconnect = { goProManager.disconnect() },
                            onForget = { goProManager.forgetConnectedDevice() },
                            onFinish = { onFinish() },
                            isConnecting = true, // Currently connecting
                            onCancelConnecting = {
                                goProManager.disconnect()
                                isAutoConnecting = false
                                showMenu = false
                            }
                        )
                    }
                    else -> {
                        if (isAutoConnecting && currentState is ConnectionState.Disconnected) {
                             // Still trying to auto-connect, but not yet in Connecting state
                            ConnectedScreen(
                                deviceName = null, // Unknown device name during initial auto-connect
                                isRecording = isRecording,
                                isProcessing = isProcessing,
                                recordingDuration = recordingDuration,
                                batteryLevel = batteryLevel,
                                remainingTime = remainingTime,
                                cameraMode = cameraMode,
                                onToggleRecording = {}, // Disabled when connecting
                                onSetMode = {}, // Disabled when connecting
                                onDisconnect = { goProManager.disconnect() },
                                onForget = { goProManager.forgetConnectedDevice() },
                                onFinish = { onFinish() },
                                isConnecting = true, // Still connecting
                                onCancelConnecting = {
                                    goProManager.disconnect()
                                    isAutoConnecting = false
                                    showMenu = false
                                }
                            )
                        } else {
                            isAutoConnecting = false // Reset if we reach scanning/disconnected normally
                            ScanningScreen(
                                connectionState = currentState,
                                scannedDevices = scannedDevices,
                                pairedDevices = pairedDevices,
                                permissionsGranted = permissionsGranted,
                                onStartScan = { goProManager.startScan() },
                                onStopScan = {
                                    goProManager.stopScan()
                                    // Also cancel any pending connection if user clicked Stop
                                    goProManager.disconnect()
                                },
                                onConnect = { device -> goProManager.connect(device) },
                                onConnectToPaired = { address -> goProManager.connectToDevice(address) },
                                onRemovePaired = { address -> goProManager.removePairedDevice(address) },
                                onFinish = { onFinish() }
                            )
                        }
                    }
                }
            }
        }
    }
}