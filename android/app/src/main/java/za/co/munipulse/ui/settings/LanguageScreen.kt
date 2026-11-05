package za.co.munipulse.ui.settings

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.auth.LanguageCodes

@Composable
fun LanguageScreen(
    selectedCode: String,
    onSave: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var selected by remember(selectedCode) { mutableStateOf(LanguageCodes.normalize(selectedCode)) }

    LaunchedEffect(Unit) {
        Log.i(TAG, "Opened language")
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
        Text(text = stringResource(R.string.language_title), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        LanguageChoice(
            selected = selected == LanguageCodes.ENGLISH,
            label = stringResource(R.string.profile_language_en),
            onSelect = { selected = LanguageCodes.ENGLISH },
        )
        LanguageChoice(
            selected = selected == LanguageCodes.ZULU,
            label = stringResource(R.string.profile_language_zu),
            onSelect = { selected = LanguageCodes.ZULU },
        )
        Spacer(modifier = Modifier.height(16.dp))
        Button(
            onClick = { onSave(selected) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(text = stringResource(R.string.language_save))
        }
    }
}

@Composable
private fun LanguageChoice(selected: Boolean, label: String, onSelect: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onSelect)
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        RadioButton(selected = selected, onClick = onSelect)
        Spacer(modifier = Modifier.width(12.dp))
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

private const val TAG = "MuniPulse"
