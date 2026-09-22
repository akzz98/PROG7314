package za.co.munipulse.ui.common

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R

@Composable
fun ListLoading(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .padding(top = 48.dp),
        contentAlignment = Alignment.Center,
    ) {
        CircularProgressIndicator()
    }
}

@Composable
fun ListEmpty(
    title: String,
    body: String = "",
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Text(text = title, style = MaterialTheme.typography.titleMedium, modifier = modifier)
    if (body.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = body, style = MaterialTheme.typography.bodyLarge)
    }
    if (actionLabel != null && onAction != null) {
        Spacer(modifier = Modifier.height(12.dp))
        Button(onClick = onAction) {
            Text(text = actionLabel)
        }
    }
}

@Composable
fun ListError(
    message: String,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Text(
        text = stringResource(R.string.list_unreachable),
        color = MaterialTheme.colorScheme.error,
        style = MaterialTheme.typography.titleMedium,
        modifier = modifier,
    )
    if (message.isNotBlank()) {
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodyLarge)
    }
    Spacer(modifier = Modifier.height(12.dp))
    Button(onClick = onRetry) {
        Text(text = stringResource(R.string.list_retry))
    }
}
