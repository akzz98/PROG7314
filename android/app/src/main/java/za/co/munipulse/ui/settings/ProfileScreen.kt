package za.co.munipulse.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.auth.WardCatalog

@Composable
fun ProfileScreen(
    displayName: String,
    email: String,
    defaultWardCode: String,
    preferredLanguage: String,
    role: String,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val ward = WardCatalog.all.firstOrNull { it.code == defaultWardCode }
    val languageLabel = stringResource(
        if (preferredLanguage == "zu") R.string.profile_language_zu else R.string.profile_language_en,
    )

    LaunchedEffect(Unit) {
        Log.i(TAG, "Opened profile")
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
        Text(text = stringResource(R.string.profile_title), style = MaterialTheme.typography.headlineMedium)
        ProfileField(label = stringResource(R.string.profile_name), value = displayName)
        if (email.isNotEmpty()) {
            ProfileField(label = stringResource(R.string.profile_email), value = maskEmail(email))
        }
        ProfileField(
            label = stringResource(R.string.settings_ward),
            value = if (ward == null) {
                defaultWardCode
            } else {
                stringResource(R.string.settings_ward_value, ward.code, ward.municipality, ward.name)
            },
        )
        ProfileField(label = stringResource(R.string.profile_language), value = languageLabel)
        ProfileField(label = stringResource(R.string.profile_role), value = role)
        // FINAL POE: UD-5 impact score and badges are not part of the Part 2 profile.
        Text(
            text = stringResource(R.string.profile_impact),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.settings_final_poe),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun ProfileField(label: String, value: String) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(text = label, style = MaterialTheme.typography.titleMedium)
        Text(text = value, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val TAG = "MuniPulse"
