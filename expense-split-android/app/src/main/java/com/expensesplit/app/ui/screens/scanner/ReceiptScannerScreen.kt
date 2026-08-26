package com.expensesplit.app.ui.screens.scanner

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Camera
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.WarningAmber
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.expensesplit.app.R
import com.expensesplit.app.core.Money
import com.expensesplit.app.ui.components.CurrencyPicker
import com.expensesplit.app.ui.components.DateField
import com.expensesplit.app.ui.components.EmptyState
import com.expensesplit.app.ui.components.formatMoney
import com.expensesplit.app.ui.theme.LocalFinanceColors
import com.google.accompanist.permissions.ExperimentalPermissionsApi
import com.google.accompanist.permissions.isGranted
import com.google.accompanist.permissions.rememberPermissionState

/**
 * Receipt scanning: a full-bleed camera with a framing guide, then a review sheet where every
 * extracted field can be corrected before anything is saved.
 *
 * Nothing is written to the database until the user confirms the review step — an OCR pass is a
 * suggestion, not a source of truth.
 */
@OptIn(ExperimentalPermissionsApi::class, ExperimentalMaterial3Api::class)
@Composable
fun ReceiptScannerScreen(
    onCancel: () -> Unit,
    onScanned: (Long) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: ScannerViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val cameraPermission = rememberPermissionState(Manifest.permission.CAMERA)

    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia(),
    ) { uri -> uri?.let { viewModel.scanFromGallery(context, it) } }

    LaunchedEffect(state.savedReceiptId) {
        state.savedReceiptId?.let(onScanned)
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.title_scan_receipt)) },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back),
                        )
                    }
                },
                actions = {
                    IconButton(
                        onClick = {
                            galleryLauncher.launch(
                                PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly),
                            )
                        },
                    ) {
                        Icon(
                            Icons.Filled.PhotoLibrary,
                            contentDescription = stringResource(R.string.action_pick_from_gallery),
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(),
            )
        },
    ) { padding ->
        Box(modifier = Modifier.fillMaxSize().padding(padding)) {
            when (state.stage) {
                ScanStage.CAMERA -> {
                    if (cameraPermission.status.isGranted) {
                        CameraCapture(
                            onCapture = { imageCapture ->
                                viewModel.captureAndScan(context, imageCapture)
                            },
                        )
                    } else {
                        PermissionPrompt(
                            onRequest = { cameraPermission.launchPermissionRequest() },
                            onPickGallery = {
                                galleryLauncher.launch(
                                    PickVisualMediaRequest(
                                        ActivityResultContracts.PickVisualMedia.ImageOnly,
                                    ),
                                )
                            },
                        )
                    }
                }

                ScanStage.PROCESSING -> ProcessingIndicator()

                ScanStage.REVIEW -> ReviewSheet(
                    state = state,
                    viewModel = viewModel,
                    onRetake = viewModel::retake,
                )

                ScanStage.ERROR -> EmptyState(
                    icon = Icons.Filled.WarningAmber,
                    title = stringResource(R.string.scan_failed_title),
                    message = state.errorMessage ?: stringResource(R.string.scan_failed_message),
                    action = {
                        Button(onClick = viewModel::retake) {
                            Text(stringResource(R.string.action_try_again))
                        }
                    },
                )
            }
        }
    }
}

/**
 * CameraX preview bound to the composition's lifecycle.
 *
 * The provider is unbound in [DisposableEffect]'s cleanup so leaving the screen releases the camera
 * immediately rather than when the activity happens to stop.
 */
@Composable
private fun CameraCapture(onCapture: (ImageCapture) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    val imageCapture = remember {
        ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY)
            .build()
    }

    DisposableEffect(lifecycleOwner) {
        val providerFuture = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null

        providerFuture.addListener(
            {
                provider = providerFuture.get()
                val preview = Preview.Builder().build().apply {
                    setSurfaceProvider(previewView.surfaceProvider)
                }
                runCatching {
                    provider?.unbindAll()
                    provider?.bindToLifecycle(
                        lifecycleOwner,
                        CameraSelector.DEFAULT_BACK_CAMERA,
                        preview,
                        imageCapture,
                    )
                }
            },
            ContextCompat.getMainExecutor(context),
        )

        onDispose { provider?.unbindAll() }
    }

    Box(modifier = Modifier.fillMaxSize()) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())

        // Framing guide: a light frame telling the user where to hold the receipt.
        Box(
            modifier = Modifier
                .align(Alignment.Center)
                .fillMaxWidth(0.86f)
                .fillMaxHeight(0.62f)
                .border(2.dp, Color.White.copy(alpha = 0.7f), RoundedCornerShape(16.dp)),
        )

        Column(
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(Color.Black.copy(alpha = 0.45f))
                .padding(vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                text = stringResource(R.string.scan_hint),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
            )
            Spacer(Modifier.height(14.dp))
            FilledIconButton(
                onClick = { onCapture(imageCapture) },
                modifier = Modifier.size(68.dp),
            ) {
                Icon(
                    Icons.Filled.Camera,
                    contentDescription = stringResource(R.string.action_capture),
                    modifier = Modifier.size(32.dp),
                )
            }
        }
    }
}

@Composable
private fun ProcessingIndicator() {
    Column(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.scan_processing),
            style = MaterialTheme.typography.bodyLarge,
        )
    }
}

@Composable
private fun PermissionPrompt(onRequest: () -> Unit, onPickGallery: () -> Unit) {
    EmptyState(
        icon = Icons.Filled.CameraAlt,
        title = stringResource(R.string.permission_camera_title),
        message = stringResource(R.string.permission_camera_message),
        action = {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Button(onClick = onRequest) {
                    Text(stringResource(R.string.action_grant_permission))
                }
                Spacer(Modifier.height(8.dp))
                OutlinedButton(onClick = onPickGallery) {
                    Text(stringResource(R.string.action_pick_from_gallery))
                }
            }
        },
    )
}

/** Editable review of everything OCR extracted, shown before anything is written to the database. */
@Composable
private fun ReviewSheet(
    state: ScannerUiState,
    viewModel: ScannerViewModel,
    onRetake: () -> Unit,
) {
    val finance = LocalFinanceColors.current
    val itemsTotal = viewModel.itemsTotalMinor()
    val statedTotal = Money.parseToMinor(state.editableTotal, state.currency) ?: 0L
    val hasMismatch = itemsTotal > 0 && statedTotal > 0 &&
        kotlin.math.abs(itemsTotal - statedTotal) > statedTotal / 10

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 16.dp),
    ) {
        Spacer(Modifier.height(8.dp))

        if (state.needsReview) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = finance.warning.copy(alpha = 0.12f),
                ),
                modifier = Modifier.fillMaxWidth(),
            ) {
                Row(modifier = Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Filled.WarningAmber, contentDescription = null, tint = finance.warning)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = stringResource(R.string.scan_low_confidence),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
        }

        OutlinedTextField(
            value = state.editableMerchant,
            onValueChange = viewModel::onMerchantChanged,
            label = { Text(stringResource(R.string.field_merchant)) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(12.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            OutlinedTextField(
                value = state.editableTotal,
                onValueChange = viewModel::onTotalChanged,
                label = { Text(stringResource(R.string.field_total)) },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            Spacer(Modifier.width(10.dp))
            CurrencyPicker(
                currencies = listOf(state.currency) + COMMON_CURRENCIES.filterNot { it == state.currency },
                selected = state.currency,
                onSelected = viewModel::onCurrencyChanged,
            )
        }

        Spacer(Modifier.height(12.dp))

        DateField(date = state.editableDate, onDateChange = viewModel::onDateChanged)

        Spacer(Modifier.height(20.dp))

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = stringResource(R.string.scan_items_found, state.items.size),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = formatMoney(itemsTotal, state.currency),
                style = MaterialTheme.typography.titleMedium,
                color = if (hasMismatch) finance.warning else MaterialTheme.colorScheme.onSurface,
            )
        }

        if (hasMismatch) {
            Text(
                text = stringResource(R.string.scan_items_mismatch),
                style = MaterialTheme.typography.bodySmall,
                color = finance.warning,
            )
        }

        Spacer(Modifier.height(8.dp))
        HorizontalDivider()

        state.items.forEachIndexed { index, item ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(text = item.name, style = MaterialTheme.typography.bodyLarge)
                    if (item.quantity > 1.0) {
                        Text(
                            text = stringResource(
                                R.string.receipt_item_quantity,
                                item.quantity.toInt(),
                                formatMoney(item.unitPriceMinor, state.currency),
                            ),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Text(
                    text = formatMoney(item.totalPriceMinor, state.currency),
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = FontWeight.Medium,
                )
                IconButton(onClick = { viewModel.onItemRemoved(index) }) {
                    Icon(
                        Icons.Filled.Close,
                        contentDescription = stringResource(R.string.action_remove),
                        modifier = Modifier.size(18.dp),
                    )
                }
            }
            HorizontalDivider()
        }

        Spacer(Modifier.height(24.dp))

        Button(
            onClick = viewModel::save,
            enabled = !state.isSaving,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_use_this_receipt))
        }

        Spacer(Modifier.height(8.dp))

        OutlinedButton(
            onClick = {
                viewModel.deleteCapturedImage()
                onRetake()
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(stringResource(R.string.action_rescan))
        }

        Spacer(Modifier.height(48.dp))
    }
}

/** Shortlist offered on the review sheet; the full list lives in the add-expense form. */
private val COMMON_CURRENCIES =
    listOf("USD", "EUR", "GBP", "JPY", "CNY", "CHF", "CAD", "AUD", "INR", "MXN", "BRL", "AED")
