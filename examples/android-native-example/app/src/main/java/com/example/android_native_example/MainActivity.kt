package com.example.android_native_example

import android.content.Intent
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.os.Bundle
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.android_native_example.ui.theme.AndroidnativeexampleTheme
import com.identity.nfc.core.NfcCachePolicy
import com.identity.nfc.core.NfcCore
import com.identity.nfc.core.NfcScanRequest
import com.identity.nfc.core.NfcScanResult
import com.identity.nfc.core.utils.NfcCoreErrorMapper
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

private data class ScanUiState(
    val citizenId: String = "",
    val status: String = "",
    val progress: Int = 0,
    val isScanning: Boolean = false,
    val isError: Boolean = false,
    val result: NfcScanResult? = null,
)

class MainActivity : ComponentActivity(), NfcAdapter.ReaderCallback {

    private var uiState by mutableStateOf(ScanUiState())
    private var nfcAdapter: NfcAdapter? = null
    private val readExecutor: ExecutorService = Executors.newSingleThreadExecutor()

    @Volatile
    private var scanRequested = false
    private val tagReadStarted = AtomicBoolean(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        uiState = initialState()

        setContent {
            AndroidnativeexampleTheme {
                NativeNfcScreen(
                    state = uiState,
                    onCitizenIdChange = { citizenId ->
                        uiState = uiState.copy(citizenId = citizenId)
                    },
                    onScanClick = ::toggleScan,
                    onClearClick = ::clearResult,
                    onOpenNfcSettings = ::openNfcSettings,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        if (scanRequested) {
            enableReaderMode()
        }
    }

    override fun onPause() {
        disableReaderMode()
        super.onPause()
    }

    override fun onDestroy() {
        scanRequested = false
        tagReadStarted.set(false)
        disableReaderMode()
        NfcCore.clearCachedScan()
        readExecutor.shutdownNow()
        super.onDestroy()
    }

    override fun onTagDiscovered(tag: Tag) {
        if (!scanRequested || !tagReadStarted.compareAndSet(false, true)) return

        val citizenId = uiState.citizenId
        runOnUiThread {
            uiState = uiState.copy(
                status = "Đã phát hiện thẻ, bắt đầu đọc...",
                progress = 0,
            )
        }

        readExecutor.execute {
            try {
                val result = NfcCore.read(
                    tag = tag,
                    request = NfcScanRequest(
                        citizenId = citizenId,
                        readImage = true,
                        cachePolicy = NfcCachePolicy.REUSE_IF_VALID,
                        language = "vi",
                    ),
                ) { progress, message ->
                    runOnUiThread {
                        if (scanRequested) {
                            uiState = uiState.copy(
                                progress = progress,
                                status = "$progress% - $message",
                            )
                        }
                    }
                }

                runOnUiThread {
                    if (scanRequested) {
                        uiState = uiState.copy(
                            status = "Đọc NFC thành công",
                            progress = 100,
                            isError = false,
                            result = result,
                        )
                    }
                }
            } catch (error: Throwable) {
                val payload = NfcCoreErrorMapper.toPayload(error, "vi")
                runOnUiThread {
                    if (scanRequested) {
                        uiState = uiState.copy(
                            status = "${payload.code}: ${payload.message}",
                            isError = true,
                        )
                    }
                }
            } finally {
                runOnUiThread {
                    tagReadStarted.set(false)
                    scanRequested = false
                    uiState = uiState.copy(isScanning = false)
                }
            }
        }
    }

    private fun initialState(): ScanUiState {
        val adapter = nfcAdapter
        return when {
            adapter == null -> ScanUiState(
                status = "Thiết bị này không hỗ trợ NFC",
                isError = true,
            )
            !adapter.isEnabled -> ScanUiState(
                status = "NFC đang tắt. Hãy bật NFC trước khi quét.",
                isError = true,
            )
            else -> ScanUiState(
                status = "Sẵn sàng. Nhập số căn cước rồi bắt đầu quét.",
            )
        }
    }

    private fun toggleScan() {
        if (scanRequested) {
            cancelScan()
            return
        }

        val citizenId = uiState.citizenId.trim()
        if (citizenId.length < 6) {
            uiState = uiState.copy(
                status = "Số căn cước phải có ít nhất 6 ký tự.",
                isError = true,
            )
            return
        }

        val adapter = nfcAdapter
        if (adapter == null) {
            uiState = uiState.copy(
                status = "Thiết bị này không hỗ trợ NFC",
                isError = true,
            )
            return
        }
        if (!adapter.isEnabled) {
            uiState = uiState.copy(
                status = "NFC đang tắt. Hãy bật NFC trước khi quét.",
                isError = true,
            )
            Toast.makeText(this, "Vui lòng bật NFC", Toast.LENGTH_LONG).show()
            return
        }

        scanRequested = true
        uiState = uiState.copy(
            status = "Đưa thẻ căn cước vào mặt lưng điện thoại.",
            progress = 0,
            isScanning = true,
            isError = false,
            result = null,
        )
        enableReaderMode()
    }

    private fun cancelScan() {
        scanRequested = false
        disableReaderMode()
        uiState = uiState.copy(
            status = "Đã hủy phiên quét.",
            isScanning = false,
            isError = false,
        )
    }

    private fun clearResult() {
        NfcCore.clearCachedScan()
        uiState = uiState.copy(
            status = "Đã xóa kết quả và cache DG2.",
            progress = 0,
            isError = false,
            result = null,
        )
    }

    private fun openNfcSettings() {
        startActivity(Intent(Settings.ACTION_NFC_SETTINGS))
    }

    private fun enableReaderMode() {
        val adapter = nfcAdapter ?: return
        if (!scanRequested || !adapter.isEnabled) return

        val flags = NfcAdapter.FLAG_READER_NFC_A or
            NfcAdapter.FLAG_READER_NFC_B or
            NfcAdapter.FLAG_READER_SKIP_NDEF_CHECK
        adapter.enableReaderMode(this, this, flags, null)
    }

    private fun disableReaderMode() {
        nfcAdapter?.disableReaderMode(this)
    }
}

@androidx.compose.runtime.Composable
@OptIn(ExperimentalMaterial3Api::class)
private fun NativeNfcScreen(
    state: ScanUiState,
    onCitizenIdChange: (String) -> Unit,
    onScanClick: () -> Unit,
    onClearClick: () -> Unit,
    onOpenNfcSettings: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(title = { Text("Android Native NFC") })
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(paddingValues)
                .padding(20.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = "Đọc trực tiếp bằng nfc-core",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                text = "Host Android thuần, không sử dụng React Native hoặc Nitro Modules.",
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            OutlinedTextField(
                value = state.citizenId,
                onValueChange = onCitizenIdChange,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Số căn cước công dân") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                enabled = !state.isScanning,
            )

            Button(
                onClick = onScanClick,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(if (state.isScanning) "Hủy quét" else "Bắt đầu quét")
            }

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedButton(
                    onClick = onClearClick,
                    modifier = Modifier.weight(1f),
                ) {
                    Text("Xóa cache")
                }
                if (state.isError && state.status.contains("NFC")) {
                    OutlinedButton(
                        onClick = onOpenNfcSettings,
                        modifier = Modifier.weight(1f),
                    ) {
                        Text("Cài đặt NFC")
                    }
                }
            }

            if (state.isScanning || state.progress > 0) {
                LinearProgressIndicator(
                    progress = { state.progress / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Text(
                text = state.status,
                color = if (state.isError) Color.Red else MaterialTheme.colorScheme.onSurfaceVariant,
            )

            state.result?.let { result ->
                ResultCard(result)
            }

            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}

@androidx.compose.runtime.Composable
private fun ResultCard(result: NfcScanResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text(
                text = "Kết quả NFC",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            ResultRow("Citizen ID", result.citizenId)
            ResultRow("Họ tên", result.fullName)
            ResultRow("Ngày sinh", result.dob)
            ResultRow("Giới tính", result.gender)
            ResultRow("Quốc tịch", result.nationality)
            ResultRow("Địa chỉ", result.permanentAddress)
            ResultRow("Ngày cấp", result.issueDate)
            ResultRow("Nơi cấp", result.issuePlace)
            ResultRow("Ngày hết hạn", result.expireDate)
            HorizontalDivider(modifier = Modifier.padding(vertical = 6.dp))
            ResultRow("DG1", "${result.dg1Bytes.size} bytes")
            ResultRow("DG2", "${result.dg2Bytes.size} bytes")
            ResultRow("DG13", "${result.dg13Bytes.size} bytes")
            ResultRow("DG14", "${result.dg14Bytes.size} bytes")
            ResultRow("SOD", "${result.sodBytes.size} bytes")
            ResultRow("Ảnh chip", "${result.imageFromChipBytes.size} bytes")
        }
    }
}

@androidx.compose.runtime.Composable
private fun ResultRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = "$label: ",
            fontWeight = FontWeight.SemiBold,
        )
        Text(text = value.ifBlank { "(trống)" })
    }
}
