package com.example.android_native_example

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.example.android_native_example.ui.theme.AndroidnativeexampleTheme
import com.vppos.nfc.core.NfcCachePolicy
import com.vppos.nfc.core.NfcScanRequest
import com.vppos.nfc.core.NfcScanResult
import com.vppos.nfc.core.NfcScanUi
import com.vppos.nfc.core.NfcScanUiListener
import com.vppos.nfc.core.utils.NfcCoreErrorPayload

private data class NfcTestUiState(
    val citizenId: String = "",
    val status: String = "Nhập số căn cước để bắt đầu quét NFC.",
    val progress: Int = 0,
    val isScanning: Boolean = false,
    val isError: Boolean = false,
    val result: NfcScanResult? = null,
)

/** Entry point for testing the local Maven nfc-core artifact and eCert WebView. */
class MainActivity : ComponentActivity() {
    private var nfcTestUiState by mutableStateOf(NfcTestUiState())

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AndroidnativeexampleTheme {
                NativeNfcTestScreen(
                    state = nfcTestUiState,
                    onCitizenIdChange = { citizenId ->
                        nfcTestUiState = nfcTestUiState.copy(citizenId = citizenId)
                    },
                    onStartNfcTest = ::startNfcTest,
                    onOpenEcert = {
                        startActivity(Intent(this@MainActivity, EcertWebViewActivity::class.java))
                    },
                )
            }
        }
    }

    private fun startNfcTest() {
        if (nfcTestUiState.isScanning) return

        nfcTestUiState = nfcTestUiState.copy(
            status = "Đang mở màn hình quét NFC.",
            progress = 0,
            isScanning = true,
            isError = false,
            result = null,
        )

        NfcScanUi.start(
            context = this,
            request = NfcScanRequest(
                citizenId = nfcTestUiState.citizenId,
                readImage = true,
                cachePolicy = NfcCachePolicy.FRESH,
                language = "vi",
            ),
            listener = object : NfcScanUiListener {
                override fun onProgress(progress: Int, message: String) {
                    nfcTestUiState = nfcTestUiState.copy(
                        progress = progress,
                        status = message,
                        isError = false,
                    )
                }

                override fun onRecoverableError(error: NfcCoreErrorPayload) {
                    nfcTestUiState = nfcTestUiState.copy(
                        status = "${error.code}: ${error.message}",
                        isError = true,
                    )
                }

                override fun onSuccess(result: NfcScanResult) {
                    nfcTestUiState = nfcTestUiState.copy(
                        status = "Đọc NFC thành công.",
                        progress = 100,
                        isScanning = false,
                        isError = false,
                        result = result,
                    )
                }

                override fun onCancelled(error: NfcCoreErrorPayload) {
                    completeWithError(error, isCancelled = true)
                }

                override fun onFailure(error: NfcCoreErrorPayload) {
                    completeWithError(error)
                }
            },
        )
    }

    private fun completeWithError(error: NfcCoreErrorPayload, isCancelled: Boolean = false) {
        nfcTestUiState = nfcTestUiState.copy(
            status = if (isCancelled) "Đã hủy: ${error.message}" else "${error.code}: ${error.message}",
            isScanning = false,
            isError = !isCancelled,
        )
    }
}

@androidx.compose.runtime.Composable
private fun NativeNfcTestScreen(
    state: NfcTestUiState,
    onCitizenIdChange: (String) -> Unit,
    onStartNfcTest: () -> Unit,
    onOpenEcert: () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        Text(
            text = "nfc-core local Maven",
            style = MaterialTheme.typography.headlineSmall,
        )
        Text(
            text = "Quét CCCD và hiển thị trực tiếp dữ liệu NfcScanResult trả về từ nfc-core.",
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
            onClick = onStartNfcTest,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isScanning,
        ) {
            Text(if (state.isScanning) "Đang quét NFC" else "Test nfc-core")
        }

        Button(
            onClick = onOpenEcert,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Mở eCert WebView")
        }

        if (state.isScanning || state.progress > 0) {
            LinearProgressIndicator(
                progress = { state.progress / 100f },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        Text(
            text = state.status,
            color = if (state.isError) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
        )

        state.result?.let { result -> NfcResultCard(result) }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@androidx.compose.runtime.Composable
private fun NfcResultCard(result: NfcScanResult) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Text("Dữ liệu từ NfcScanResult", style = MaterialTheme.typography.titleMedium)
            NfcResultRow("Citizen ID", result.citizenId)
            NfcResultRow("Họ tên", result.fullName)
            NfcResultRow("Ngày sinh", result.dob)
            NfcResultRow("Giới tính", result.gender)
            NfcResultRow("Quốc tịch", result.nationality)
            NfcResultRow("Địa chỉ", result.permanentAddress)
            NfcResultRow("Ngày cấp", result.issueDate)
            NfcResultRow("Nơi cấp", result.issuePlace)
            NfcResultRow("Ngày hết hạn", result.expireDate)
            NfcResultRow("Ảnh chip", "${result.imageFromChipBytes.size} bytes (${result.chipImageMimeType})")
            NfcResultRow("DG1", "${result.dg1Bytes.size} bytes")
            NfcResultRow("DG2", "${result.dg2Bytes.size} bytes")
            NfcResultRow("DG13", "${result.dg13Bytes.size} bytes")
            NfcResultRow("DG14", "${result.dg14Bytes.size} bytes")
            NfcResultRow("SOD", "${result.sodBytes.size} bytes")
        }
    }
}

@androidx.compose.runtime.Composable
private fun NfcResultRow(label: String, value: String) {
    Text(
        text = "$label: ${value.ifBlank { "(trống)" }}",
        style = MaterialTheme.typography.bodyMedium,
    )
}
