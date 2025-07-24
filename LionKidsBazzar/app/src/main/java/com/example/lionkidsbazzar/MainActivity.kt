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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Remove
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.runtime.Composable




class MainActivity : ComponentActivity() {
    private var nfcAdapter: NfcAdapter? = null
    private var pendingIntent: PendingIntent? = null
    private var currentBalance by mutableStateOf(0)
    private var pendingOperation by mutableStateOf<Pair<Mode, Int>?>(null)
    private var lastReadTag by mutableStateOf<Tag?>(null)
    private var operationResult by mutableStateOf<OperationResult?>(null)
    private var cardInfo by mutableStateOf<CardInfo?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        nfcAdapter = NfcAdapter.getDefaultAdapter(this)
        if (nfcAdapter == null) {
            showToast("NFC is not available on this device")
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
                    pendingOperation = pendingOperation,
                    operationResult = operationResult,
                    cardInfo = cardInfo,
                    onBalanceChange = { newBalance -> currentBalance = newBalance },
                    onWriteToTag = { tag -> writeTag(tag, currentBalance.toString()) },
                    onSetPendingOperation = { mode, amount ->
                        pendingOperation = mode to amount
                        operationResult = OperationResult.WaitingForCard(mode, amount)
                    },
                    onClearPendingOperation = {
                        pendingOperation = null
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

            val tagId = bytesToHexString(it.id)
            val currentName = cardInfo?.name ?: "NFC Card"
            cardInfo = CardInfo(tagId, currentName)

            pendingOperation?.let { (mode, amount) ->
                when (mode) {
                    Mode.TOPUP -> {
                        currentBalance += amount
                        writeTag(it, currentBalance.toString())
                        operationResult = OperationResult.Success(
                            mode = Mode.TOPUP,
                            amount = amount,
                            newBalance = currentBalance
                        )
                        showToast("Top up $amount points successful. Current balance: $currentBalance points")
                    }
                    Mode.PAYMENT -> {
                        if (amount <= currentBalance) {
                            currentBalance -= amount
                            writeTag(it, currentBalance.toString())
                            operationResult = OperationResult.Success(
                                mode = Mode.PAYMENT,
                                amount = amount,
                                newBalance = currentBalance
                            )
                            showToast("Payment of $amount points successful. Current balance: $currentBalance points")
                        } else {
                            operationResult = OperationResult.Failure(
                                mode = Mode.PAYMENT,
                                amount = amount,
                                message = "Insufficient balance"
                            )
                            showToast("Insufficient balance")
                        }
                    }
                    else -> {}
                }
                pendingOperation = null
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
                    currentBalance = text.toIntOrNull() ?: 0
                    showToast("Current balance: $currentBalance points")
                }
            } catch (e: Exception) {
                e.printStackTrace()
                showToast("Failed to read card")
            } finally {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        } else {
            showToast("Tag does not support NDEF")
        }
    }

    private fun writeTag(tag: Tag, data: String) {
        val ndef = Ndef.get(tag)
        if (ndef != null) {
            try {
                ndef.connect()
                val message = NdefMessage(
                    arrayOf(
                        NdefRecord.createTextRecord("en", data)
                    )
                )
                ndef.writeNdefMessage(message)
            } catch (e: IOException) {
                e.printStackTrace()
                showToast("Failed to write data to card")
            } finally {
                try {
                    ndef.close()
                } catch (e: IOException) {
                    e.printStackTrace()
                }
            }
        } else {
            showToast("Tag does not support NDEF")
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

    private fun showToast(message: String) {
        Toast.makeText(this, message, Toast.LENGTH_SHORT).show()
    }
}

data class CardInfo(val id: String, var name: String)

sealed class OperationResult {
    data class WaitingForCard(val mode: Mode, val amount: Int) : OperationResult()
    data class Success(val mode: Mode, val amount: Int, val newBalance: Int) : OperationResult()
    data class Failure(val mode: Mode, val amount: Int, val message: String) : OperationResult()
}

@Composable
fun LionKidsBazzarApp(
    currentBalance: Int,
    pendingOperation: Pair<Mode, Int>?,
    operationResult: OperationResult?,
    cardInfo: CardInfo?,
    onBalanceChange: (Int) -> Unit,
    onWriteToTag: (Tag) -> Unit,
    onSetPendingOperation: (Mode, Int) -> Unit,
    onClearPendingOperation: () -> Unit,
    onSetOperationResult: (OperationResult) -> Unit,
    onUpdateCardInfo: (CardInfo) -> Unit
) {
    var showDialog by remember { mutableStateOf<Mode?>(null) }
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
                    onClick = { showDialog = Mode.TOPUP },
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
                    onClick = { showDialog = Mode.PAYMENT },
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
                                    onUpdateCardInfo(cardInfo.copy(name = editedName))
                                }
                                showEditDialog = false
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

    // Transaction Dialog
    if (showDialog != null || operationResult != null) {
        Dialog(
            onDismissRequest = {
                if (operationResult is OperationResult.WaitingForCard) {
                    return@Dialog
                }
                showDialog = null
                onClearPendingOperation()
                currentAmount = 0
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
                                text = "Tap Your Card",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Please tap your NFC card to ${if (result.mode == Mode.TOPUP) "top up" else "pay"} ${result.amount} points",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            CircularProgressIndicator()
                        }
                        is OperationResult.Success -> {
                            Text(
                                text = if (result.mode == Mode.TOPUP) "Top Up Successful" else "Payment Successful",
                                style = MaterialTheme.typography.headlineSmall,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFF4CAF50)
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "${if (result.mode == Mode.TOPUP) "Top up" else "Payment"} of ${result.amount} points completed",
                                textAlign = TextAlign.Center,
                                modifier = Modifier.fillMaxWidth()
                            )
                            Text(
                                text = "Current balance: ${result.newBalance} points",
                                fontWeight = FontWeight.Bold,
                                modifier = Modifier.padding(top = 8.dp)
                            )
                            Spacer(modifier = Modifier.height(24.dp))
                            Button(
                                onClick = {
                                    onClearPendingOperation()
                                    showDialog = null
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
                                text = if (result.mode == Mode.TOPUP) "Top Up Failed" else "Payment Failed",
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
                                    onClearPendingOperation()
                                    showDialog = null
                                },
                                colors = ButtonDefaults.buttonColors(
                                    containerColor = Color(0xFFF44336),
                                    contentColor = Color.White
                                )
                            ) {
                                Text("OK")
                            }
                        }
                        else -> {
                            Text(
                                text = if (showDialog == Mode.TOPUP) "Top Up" else "Payment",
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
                                            onSetPendingOperation(showDialog!!, currentAmount)
                                            currentAmount = 0
                                        }
                                    },
                                    modifier = Modifier.weight(1f),
                                    colors = ButtonDefaults.buttonColors(
                                        containerColor = Color(0xFFf4bd6c),
                                        contentColor = Color.Black
                                    )
                                ) {
                                    Text("Confirm")
                                }
                                Button(
                                    onClick = {
                                        showDialog = null
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
    VIEW, TOPUP, PAYMENT
}

@Preview(showBackground = true, showSystemUi = true)
@Composable
fun PreviewLionKidsBazzarApp() {
    LionKidsBazzarTheme {
        LionKidsBazzarApp(
            currentBalance = 100,
            pendingOperation = null,
            operationResult = null,
            cardInfo = CardInfo("0x0123456789ABCDEF", "NFC Card"),
            onBalanceChange = {},
            onWriteToTag = {},
            onSetPendingOperation = { _, _ -> },
            onClearPendingOperation = {},
            onSetOperationResult = {},
            onUpdateCardInfo = {}
        )
    }
}