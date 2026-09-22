package za.co.munipulse.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.auth.WardCatalog

@Composable
fun SettingsScreen(
    displayName: String,
    email: String,
    defaultWardCode: String,
    profileNote: String,
    onWardSelected: (String) -> Unit,
    onOpenNotifications: () -> Unit,
    onSignOut: () -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var wardMenuOpen by remember { mutableStateOf(false) }
    val ward = WardCatalog.all.firstOrNull { it.code == defaultWardCode }

    LaunchedEffect(Unit) {
        Log.i(TAG, "Opened settings")
    }

    BackHandler(onBack = onBack)

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        TextButton(onClick = onBack) {
            Text(text = stringResource(R.string.settings_back))
        }
        Text(text = stringResource(R.string.settings_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.settings_account), style = MaterialTheme.typography.titleMedium)
        Text(text = displayName, style = MaterialTheme.typography.bodyLarge)
        if (email.isNotEmpty()) {
            Text(text = maskEmail(email), style = MaterialTheme.typography.bodyLarge)
        }
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.settings_ward), style = MaterialTheme.typography.titleMedium)
        Box {
            OutlinedButton(onClick = { wardMenuOpen = true }, modifier = Modifier.fillMaxWidth()) {
                Text(
                    text = if (ward == null) {
                        defaultWardCode
                    } else {
                        stringResource(R.string.settings_ward_value, ward.code, ward.municipality, ward.name)
                    },
                )
            }
            DropdownMenu(expanded = wardMenuOpen, onDismissRequest = { wardMenuOpen = false }) {
                WardCatalog.all.forEach { option ->
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(
                                    R.string.settings_ward_value,
                                    option.code,
                                    option.municipality,
                                    option.name,
                                ),
                            )
                        },
                        onClick = {
                            wardMenuOpen = false
                            onWardSelected(option.code)
                        },
                    )
                }
            }
        }
        if (profileNote.isNotEmpty()) {
            Text(
                text = profileNote,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedButton(onClick = onOpenNotifications, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.settings_notifications))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onSignOut, modifier = Modifier.fillMaxWidth()) {
            Text(text = stringResource(R.string.sign_out))
        }
    }
}

internal fun maskEmail(email: String): String {
    val at = email.indexOf('@')
    if (at <= 0) {
        return email
    }
    return email.substring(0, 1) + "***" + email.substring(at)
}

private const val TAG = "MuniPulse"
