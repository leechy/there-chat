package com.therechat.app.feature.map

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Chat
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material.icons.outlined.Campaign
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.MyLocation
import androidx.compose.material.icons.outlined.Notifications
import androidx.compose.material.icons.outlined.Remove
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ZoomIn
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FilledTonalIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.google.android.gms.location.LocationServices
import com.google.android.gms.maps.CameraUpdateFactory
import com.google.android.gms.maps.model.CameraPosition
import com.google.android.gms.maps.model.LatLng
import com.google.maps.android.compose.GoogleMap
import com.google.maps.android.compose.MapProperties
import com.google.maps.android.compose.MapUiSettings
import com.google.maps.android.compose.MarkerComposable
import com.google.maps.android.compose.rememberCameraPositionState
import com.google.maps.android.compose.rememberUpdatedMarkerState
import com.therechat.app.R
import com.therechat.app.core.Chat
import com.therechat.app.core.ChatKind
import kotlinx.coroutines.launch
import kotlinx.coroutines.tasks.await

private val Paris = LatLng(48.8530, 2.3499)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapHomeScreen(vm: MapViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val camera = rememberCameraPositionState { position = CameraPosition.fromLatLngZoom(Paris, 16f) }
    var hasLocation by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_COARSE_LOCATION) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    var openCluster by remember { mutableStateOf<ChatCluster?>(null) }

    @SuppressLint("MissingPermission")
    suspend fun centerOnUser() {
        if (!hasLocation) return
        val location = runCatching {
            LocationServices.getFusedLocationProviderClient(context).lastLocation.await()
        }.getOrNull() ?: return
        camera.animate(CameraUpdateFactory.newLatLngZoom(LatLng(location.latitude, location.longitude), 16f))
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { granted ->
        hasLocation = granted.values.any { it }
        scope.launch { centerOnUser() }
    }

    LaunchedEffect(Unit) {
        if (hasLocation) centerOnUser()
        else permissionLauncher.launch(
            arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)
        )
    }

    LaunchedEffect(camera.isMoving) {
        if (!camera.isMoving) {
            camera.projection?.visibleRegion?.latLngBounds?.let(vm::onCameraIdle)
        }
    }

    Box(Modifier.fillMaxSize()) {
        GoogleMap(
            modifier = Modifier.fillMaxSize(),
            cameraPositionState = camera,
            properties = MapProperties(isMyLocationEnabled = hasLocation),
            uiSettings = MapUiSettings(
                zoomControlsEnabled = false,
                myLocationButtonEnabled = false,
                mapToolbarEnabled = false,
                compassEnabled = false,
            ),
        ) {
            state.clusters.forEach { cluster ->
                MarkerComposable(
                    keys = arrayOf(cluster.id),
                    state = rememberUpdatedMarkerState(position = cluster.position),
                    title = cluster.chats.singleOrNull()?.title,
                    onClick = {
                        openCluster = cluster
                        true
                    },
                ) {
                    ChatMarker(cluster)
                }
            }
        }

        Box(Modifier.fillMaxSize().safeDrawingPadding().padding(16.dp)) {
            // Top bar
            Row(Modifier.fillMaxWidth().align(Alignment.TopCenter)) {
                MapButton(Icons.Outlined.Settings, stringResource(R.string.settings)) {}
                Spacer(Modifier.weight(1f))
                MapButton(Icons.Outlined.Notifications, stringResource(R.string.notifications)) {}
            }

            // Zoom controls
            Column(
                Modifier.align(Alignment.CenterEnd),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        IconButton(onClick = { scope.launch { camera.animate(CameraUpdateFactory.zoomIn()) } }) {
                            Icon(Icons.Outlined.Add, stringResource(R.string.zoom_in))
                        }
                        ZoomScale(state.zoomProgress, Modifier.width(6.dp).height(64.dp))
                        IconButton(onClick = { scope.launch { camera.animate(CameraUpdateFactory.zoomOut()) } }) {
                            Icon(Icons.Outlined.Remove, stringResource(R.string.zoom_out))
                        }
                    }
                }
                MapButton(Icons.Outlined.MyLocation, stringResource(R.string.my_location)) {
                    if (hasLocation) scope.launch { centerOnUser() }
                    else permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_COARSE_LOCATION))
                }
            }

            // Bottom bar
            Column(
                Modifier.align(Alignment.BottomCenter).fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                val hint = when {
                    state.tooFar -> stringResource(R.string.zoom_in_to_see_chats)
                    else -> state.error
                }
                if (hint != null) {
                    Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainer, shadowElevation = 3.dp) {
                        Row(
                            Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(6.dp),
                        ) {
                            if (state.tooFar) Icon(Icons.Outlined.ZoomIn, null, Modifier.size(18.dp))
                            Text(hint, style = MaterialTheme.typography.labelLarge)
                        }
                    }
                }
                Row(Modifier.fillMaxWidth()) {
                    ExtendedFloatingActionButton(
                        onClick = {},
                        icon = { Icon(Icons.Outlined.AddComment, null) },
                        text = { Text(stringResource(R.string.new_chat)) },
                        containerColor = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.weight(1f))
                    ExtendedFloatingActionButton(
                        onClick = {},
                        icon = { Icon(Icons.AutoMirrored.Outlined.Chat, null) },
                        text = { Text(stringResource(R.string.my_chats)) },
                        containerColor = MaterialTheme.colorScheme.surfaceContainer,
                    )
                }
            }
        }
    }

    openCluster?.let { cluster ->
        ModalBottomSheet(onDismissRequest = { openCluster = null }) {
            if (cluster.chats.size > 1) {
                Text(
                    stringResource(R.string.chats_here, cluster.chats.size),
                    style = MaterialTheme.typography.titleMedium,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                )
            }
            LazyColumn(Modifier.padding(bottom = 24.dp)) {
                items(cluster.chats, key = Chat::id) { chat ->
                    ListItem(
                        headlineContent = { Text(chat.title) },
                        leadingContent = { Text(chat.emoji, style = MaterialTheme.typography.titleLarge) },
                        trailingContent = {
                            if (chat.kind == ChatKind.CHANNEL) Icon(Icons.Outlined.Campaign, null)
                        },
                        modifier = Modifier.clickable { openCluster = null /* TODO: open chat */ },
                    )
                    HorizontalDivider()
                }
            }
        }
    }
}

@Composable
private fun MapButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit) {
    FilledTonalIconButton(
        onClick = onClick,
        modifier = Modifier.size(48.dp),
        colors = IconButtonDefaults.filledTonalIconButtonColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Icon(icon, label)
    }
}

/** The accent part marks the zoom range where chats are visible; the dot is the current zoom. */
@Composable
private fun ZoomScale(progress: Float, modifier: Modifier) {
    val accent = MaterialTheme.colorScheme.primary
    val track = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.3f)
    Box(modifier.background(track, CircleShape)) {
        val visibleFraction = 0.55f
        Box(Modifier.fillMaxWidth().fillMaxHeight(visibleFraction).background(accent, CircleShape))
        val dot = if (progress < 0) visibleFraction + (1 - visibleFraction) * minOf(-progress, 1f)
        else visibleFraction * (1 - minOf(progress, 1f))
        Box(
            Modifier
                .offset(x = (-1).dp, y = (64 * dot - 4).dp)
                .size(8.dp)
                .background(MaterialTheme.colorScheme.onSurface, CircleShape)
        )
    }
}


@Composable
private fun ChatMarker(cluster: ChatCluster) {
    val accent = MaterialTheme.colorScheme.primary
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(
            Modifier
                .sizeIn(minWidth = 36.dp, minHeight = 36.dp)
                .background(MaterialTheme.colorScheme.surface, RoundedCornerShape(8.dp))
                .border(2.5.dp, accent, RoundedCornerShape(8.dp))
                .padding(4.dp),
            contentAlignment = Alignment.Center,
        ) {
            val single = cluster.chats.singleOrNull()
            if (single != null) Text(single.emoji, style = MaterialTheme.typography.titleMedium)
            else Text("${cluster.chats.size}", color = accent, fontWeight = FontWeight.Bold)
        }
        Box(Modifier.size(width = 10.dp, height = 7.dp).background(accent, TriangleShape))
    }
}
