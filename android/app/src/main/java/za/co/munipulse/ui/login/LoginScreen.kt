package za.co.munipulse.ui.login

import android.app.Activity
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import za.co.munipulse.R
import za.co.munipulse.auth.SignInUiState

@Composable
fun LoginScreen(
    state: SignInUiState,
    onGoogleSignIn: (Activity) -> Unit,
    modifier: Modifier = Modifier,
) {
    val activity = LocalContext.current as Activity
    val banner = when (state) {
        SignInUiState.MissingConfig -> stringResource(R.string.login_firebase_missing)
        SignInUiState.MissingWebClient -> stringResource(R.string.login_web_client_missing)
        is SignInUiState.Failed -> state.message
        else -> null
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(text = stringResource(R.string.app_name), style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = stringResource(R.string.login_subtitle), style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(32.dp))
        if (state is SignInUiState.Working) {
            CircularProgressIndicator()
            Spacer(modifier = Modifier.height(12.dp))
            Text(text = stringResource(R.string.login_working))
        } else {
            Button(
                onClick = { onGoogleSignIn(activity) },
                enabled = state !is SignInUiState.MissingConfig &&
                    state !is SignInUiState.MissingWebClient &&
                    state !is SignInUiState.Checking,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(text = stringResource(R.string.login_google))
            }
        }
        if (banner != null) {
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                text = banner,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}
