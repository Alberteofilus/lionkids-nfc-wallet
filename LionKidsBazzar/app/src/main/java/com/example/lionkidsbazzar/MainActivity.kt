package com.example.lionkidsbazzar

import android.app.PendingIntent
import android.content.Intent
import android.nfc.NdefMessage
import android.nfc.NdefRecord
import android.nfc.NfcAdapter
import android.nfc.Tag
import android.nfc.tech.Ndef
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import java.io.IOException
import java.nio.charset.StandardCharsets
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable

class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var currentBalance by mutableStateOf(0)
    private var currentOperation by mutableStateOf<Pair<Mode, Any>?>(null)
    private var lastReadTag by mutableStateOf<Tag?>(null)
    private var operationResult by mutableStateOf<OperationResult?>(null)
    private var cardInfo by mutableStateOf<CardInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showQuickToast("NFC is not available on this device")
            finish()
            return
        }

        pendingIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, javaClass).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_MUTABLE
        )

        setContent {
            LionKidsBazzarTheme {
                LionKidsBazzarApp(
                    currentBalance = currentBalance,
                    currentOperation = currentOperation,
                    operationResult = operationResult,
                    cardInfo = cardInfo,
                    onBalanceChange = { newBalance -> currentBalance = newBalance },
                    onWriteToTag = { tag -> writeTag(tag, currentBalance) },
                    onSetCurrentOperation = { mode, data ->
                        currentOperation = mode to data
                        operationResult = OperationResult.WaitingForCard(mode, data)
                    },
                    onClearCurrentOperation = {
                        currentOperation = null
                        operationResult = null
                    },
                    onSetOperationResult = { result -> operationResult = result },
                    onUpdateCardInfo = { info -> cardInfo = info }
                )
            }
        }

        handleIntent(intent)
    }

    override fun onResume() {
        super.onResume()
        nfcAdapter?.enableForegroundDispatch(this, pendingIntent, null, null)
    }

    override fun onPause() {
        super.onPause()
        nfcAdapter?.disableForegroundDispatch(this)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent) {
        val tag: Tag? = intent.getParcelableExtra(NfcAdapter.EXTRA_TAG)
        tag?.let {
            lastReadTag = it
            readTag(it)

            currentOperation?.let { (mode, data) ->
                when (mode) {
                    Mode.TOPUP -> {
                        val amount = data as Int
                        currentBalance += amount
                        writeTag(it, currentBalance)
                        operationResult = OperationResult.Success(
                            mode = Mode.TOPUP,
                            amount = amount,
                            newBalance = currentBalance
                        )
                        showQuickToast("Top up $amount points successful.")
                    }
                    Mode.PAYMENT -> {
                        val amount = data as Int
                        if (amount <= currentBalance) {
                            currentBalance -= amount
                            writeTag(it, currentBalance)
                            operationResult = OperationResult.Success(
                                mode = Mode.PAYMENT,
                                amount = amount,
                                newBalance = currentBalance
                            )
                            showQuickToast("Payment of $amount points successful.")
                        } else {
                            operationResult = OperationResult.Failure(
                                mode = Mode.PAYMENT,
                                amount = amount,
                                message = "Insufficient balance"
                            )
                            showQuickToast("Insufficient balance")
                        }
                    }
                    Mode.RENAME -> {
                        val newName = data as String
                        cardInfo = cardInfo?.copy(name = newName)
                        writeTag(it, currentBalance)
                        operationResult = OperationResult.Success(
                            mode = Mode.RENAME,
                            amount = 0,
                            newBalance = currentBalance
                        )
                        showQuickToast("Card renamed to $newName")
                    }
                    else -> {}
                }
                currentOperation = null
            }
        }
    }

    private fun readTag(tag: Tag) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                val ndefMessage = ndef.ndefMessage
                if (ndefMessage != null && ndefMessage.records.isNotEmpty()) {
                    val record = ndefMessage.records[0]
                    val payload = record.payload
                    val text = String(payload, 3, payload.size - 3, StandardCharsets.UTF_8)

                    // Parse data in format "name|balance"
                    val parts = text.split("|")
                    if (parts.size == 2) {
                        val (name, balanceStr) = parts
                        currentBalance = balanceStr.toIntOrNull() ?: 0
                        cardInfo = CardInfo(bytesToHexString(tag.id), name)
                    } else {
                        // Default values if format is invalid
                        currentBalance = 0
                        cardInfo = CardInfo(bytesToHexString(tag.id), "LionCard")
                        writeTag(tag, currentBalance) // Write with correct format
                    }
                } else {
                    // Initialize new card with default values
                    currentBalance = 0
                    cardInfo = CardInfo(bytesToHexString(tag.id), "LionCard")
                    writeTag(tag, currentBalance)
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showQuickToast("Failed to read card")
            } finally {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        } else {
            showQuickToast("Tag does not support NDEF")
        }
    }

    private fun writeTag(tag: Tag, balance: Int) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                // Format data as "name|balance"
                val cardName = cardInfo?.name ?: "LionCard"
                val data = "$cardName|$balance"
                val message = NdefMessage(
                    arrayOf(
                        NdefRecord.createTextRecord("en", data)
                    )
                )
                ndef.writeNdefMessage(message)
            } catch (e: IOException) {
                e.printStackTrace()
                showQuickToast("Failed to write data to card")
            } finally {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        } else {
            showQuickToast("Tag does not support NDEF")
        }
    }

    private fun bytesToHexString(src: ByteArray): String {
        val stringBuilder = StringBuilder("0x")
        if (src.isEmpty()) {
            return "0x00"
        }
        val buffer = CharArray(2)
        for (i in src.indices) {
            buffer[0] = Character.forDigit(src[i].toInt() ushr 4 and 0x0F, 16)
            buffer[1] = Character.forDigit(src[i].toInt() and 0x0F, 16)
            stringBuilder.append(buffer)
        }
        return stringBuilder.toString()
    }

    private fun showQuickToast(message: String) {
        val toast = Toast.makeText(this, message, Toast.LENGTH_SHORT)
        toast.view?.postDelayed({ toast.cancel() }, 700)
        toast.show()
    }
}

data class CardInfo(val id: String, var name: String)

sealed class OperationResult {
    data class WaitingForCard(val mode: Mode, val data: Any) : OperationResult()
    data class Success(val mode: Mode, val amount: Int, val newBalance: Int) : OperationResult()
    data class Failure(val mode: Mode, val amount: Int, val message: String) : OperationResult()
}

@Composable
fun LionKidsBazzarApp(
    currentBalance: Int,
    currentOperation: Pair<Mode, Any>?,
    operationResult: OperationResult?,
    cardInfo: CardInfo?,
    onBalanceChange: (Int) -> Unit,
    onWriteToTag: (Tag) -> Unit,
    onSetCurrentOperation: (Mode, Any) -> Unit,
    onClearCurrentOperation: () -> Unit,
    onSetOperationResult: (OperationResult) -> Unit,
    onUpdateCardInfo: (CardInfo) -> Unit
) {
    var showTopUpDialog by remember { mutableStateOf(false) }
    var showPaymentDialog by remember { mutableStateOf(false) }
    var showCardInfoDialog by remember { mutableStateOf(false) }
    var showEditDialog by remember { mutableStateOf(false) }
    var currentAmount by remember { mutableStateOf(0) }
    var editedName by remember { mutableStateOf(cardInfo?.name ?: "") }
    val context = LocalContext.current

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.White)
            .padding(16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Column(
            modifier = Modifier.weight(1f),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Image(
                painter = painterResource(id = R.drawable.logo_sm_1),
                contentDescription = "Lion Kids Logo",
                modifier = Modifier
                    .height(300.dp)
                    .width(1000.dp)
                    .padding(bottom = 10.dp)
            )

            Text(
                text = "Balance: $currentBalance points",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color(0xFF000000)
            )

            Spacer(modifier = Modifier.height(40.dp))

            Row(
                horizontalArrangement = Arrangement.spacedBy(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Button(
                    onClick = { showTopUpDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = operationResult == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF4BD6C),
                        contentColor = Color.Black
                    )
                ) {
                    Text("Top Up")
                }

                Button(
                    onClick = { showPaymentDialog = true },
                    modifier = Modifier.weight(1f),
                    enabled = operationResult == null,
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFFF4BD6C),
                        contentColor = Color.Black
                    )
                ) {
                    Text("Pay")
                }
            }
        }

        // Card Info Section
        if (cardInfo != null) {
            Surface(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 16.dp)
                    .clickable { showCardInfoDialog = true },
                shape = RoundedCornerShape(16.dp),
                color = Color(0xFFF5F5F5),
                shadowElevation = 4.dp
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Column {
                            Text(
                                text = "ID: ${cardInfo.id}",
                                fontWeight = FontWeight.Normal
                            )
                            Text(
                                text = "Name: ${cardInfo.name}",
                                fontWeight = FontWeight.Normal
                            )
                        }
                        IconButton(
                            onClick = {
                                editedName = cardInfo.name
                                showEditDialog = true
                            }
                        ) {
                            Icon(Icons.Default.Edit, contentDescription = "Edit")
                        }
                    }
                }
            }
        }
    }

    // Top Up Dialog
    if (showTopUpDialog) {
        Dialog(
            onDismissRequest = { showTopUpDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Top Up",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = { if (currentAmount > 0) currentAmount -= 5 },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        Text(
                            text = "$currentAmount points",
                            style = MaterialTheme.typography.headlineSmall,
                            fontSize = 24.sp
                        )
                        IconButton(
                            onClick = { currentAmount += 5 },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                if (currentAmount > 0) {
                                    onSetCurrentOperation(Mode.TOPUP, currentAmount)
                                    showTopUpDialog = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Confirm")
                        }
                        Button(
                            onClick = {
                                showTopUpDialog = false
                                currentAmount = 0
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD1D1),
                                contentColor = Color(0xFFB00020)
                            )
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    // Payment Dialog
    if (showPaymentDialog) {
        Dialog(
            onDismissRequest = { showPaymentDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Payment",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        IconButton(
                            onClick = { if (currentAmount > 0) currentAmount -= 5 },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Remove, contentDescription = "Decrease")
                        }
                        Text(
                            text = "$currentAmount points",
                            style = MaterialTheme.typography.headlineSmall,
                            fontSize = 24.sp
                        )
                        IconButton(
                            onClick = { currentAmount += 5 },
                            colors = IconButtonDefaults.iconButtonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        ) {
                            Icon(Icons.Default.Add, contentDescription = "Increase")
                        }
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                if (currentAmount > 0) {
                                    onSetCurrentOperation(Mode.PAYMENT, currentAmount)
                                    showPaymentDialog = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Confirm")
                        }
                        Button(
                            onClick = {
                                showPaymentDialog = false
                                currentAmount = 0
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD1D1),
                                contentColor = Color(0xFFB00020)
                            )
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    // Card Info Dialog
    if (showCardInfoDialog && cardInfo != null) {
        Dialog(
            onDismissRequest = { showCardInfoDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Card Information",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(text = "ID: ${cardInfo.id}")
                    Text(text = "Name: ${cardInfo.name}")
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                editedName = cardInfo.name
                                showCardInfoDialog = false
                                showEditDialog = true
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Edit")
                        }
                        Button(
                            onClick = { showCardInfoDialog = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD1D1),
                                contentColor = Color(0xFFB00020)
                            )
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
    }

    // Edit Name Dialog
    if (showEditDialog && cardInfo != null) {
        Dialog(
            onDismissRequest = { showEditDialog = false }
        ) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Edit Card Name",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    OutlinedTextField(
                        value = editedName,
                        onValueChange = { editedName = it },
                        label = { Text("Name") },
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(16.dp)
                    ) {
                        Button(
                            onClick = {
                                if (editedName.isNotEmpty()) {
                                    onSetCurrentOperation(Mode.RENAME, editedName)
                                    showEditDialog = false
                                }
                            },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFF4BD6C),
                                contentColor = Color.Black
                            )
                        ) {
                            Text("Save")
                        }
                        Button(
                            onClick = { showEditDialog = false },
                            modifier = Modifier.weight(1f),
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFD1D1),
                                contentColor = Color(0xFFB00020)
                            )
                        ) {
                            Text("Cancel")
                        }
                    }
                }
            }
        }
    }

    // Operation Waiting/Success/Failure Dialog
    if (operationResult != null) {
        Dialog(
            onDismissRequest = {
                if (operationResult is OperationResult.WaitingForCard) {
                    return@Dialog
                }
                onClearCurrentOperation()
            }
        ) {
            Surface(
                modifier = Modifier
                    .width(300.dp)
                    .padding(16.dp),
                shape = MaterialTheme.shapes.medium,
                color = MaterialTheme.colorScheme.surface
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    when (val result = operationResult) {
                        is OperationResult.WaitingForCard -> {
                            Text(
                                text = when (result.mode) {
                                    Mode.TOPUP -> "Tap to Top Up"
                                    Mode.PAYMENT -> "Tap to Pay"
                                    Mode.RENAME -> "Tap to Rename"
                                    else -> "Tap Your Card"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = when (result.mode) {
                                    Mode.TOPUP -> "Please tap your NFC card to top up ${result.data} points"
                                    Mode.PAYMENT -> "Please tap your NFC card to pay ${result.data} points"
                                    Mode.RENAME -> "Please tap your NFC card to rename to '${result.data}'"
                                    else -> "Please tap your NFC card"
                                },
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            CircularProgressIndicator()
                        }
                        is OperationResult.Success -> {
                            Text(
                                text = when (result.mode) {
                                    Mode.TOPUP -> "Top Up Successful"
                                    Mode.PAYMENT -> "Payment Successful"
                                    Mode.RENAME -> "Rename Successful"
                                    else -> "Operation Successful"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = when (result.mode) {
                                    Mode.TOPUP -> "Top up of ${result.amount} points completed"
                                    Mode.PAYMENT -> "Payment of ${result.amount} points completed"
                                    Mode.RENAME -> "Card renamed successfully"
                                    else -> "Operation completed"
                                },
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    onClearCurrentOperation()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFF4CAF50),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("OK")
                            }
                        }
                        is OperationResult.Failure -> {
                            Text(
                                text = when (result.mode) {
                                    Mode.TOPUP -> "Top Up Failed"
                                    Mode.PAYMENT -> "Payment Failed"
                                    Mode.RENAME -> "Rename Failed"
                                    else -> "Operation Failed"
                                },
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFF44336)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = result.message,
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    onClearCurrentOperation()
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF44336),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("OK")
                            }
                        }null -> println("Status is null")
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LionKidsBazzarTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = lightColorScheme(
            primary = Color(0xFF6200EE),
            onPrimary = Color.White,
            primaryContainer = Color(0xFFBB86FC),
            onPrimaryContainer = Color.Black,
            error = Color(0xFFB00020),
            onError = Color.White
        ),
        content = content
    )
}

enum class Mode {
    VIEW, TOPUP, PAYMENT, RENAME
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewLionKidsBazzarApp() {
    LionKidsBazzarTheme {
        LionKidsBazzarApp(
            currentBalance = 100,
            currentOperation = null,
            operationResult = null,
            cardInfo = CardInfo("0x0123456789ABCDEF", "LionCard"),
            onBalanceChange = {},
            onWriteToTag = {},
            onSetCurrentOperation = { _, _ -> },
            onClearCurrentOperation = {},
            onSetOperationResult = {},
            onUpdateCardInfo = {}
        )
    }
}