package za.co.munipulse.ui.onboarding

import android.util.Log
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import za.co.munipulse.R
import za.co.munipulse.ui.theme.PulseTeal

private data class OnboardingPage(val titleRes: Int, val bodyRes: Int)

private val pages = listOf(
    OnboardingPage(R.string.onboarding_page1_title, R.string.onboarding_page1_body),
    OnboardingPage(R.string.onboarding_page2_title, R.string.onboarding_page2_body),
    OnboardingPage(R.string.onboarding_page3_title, R.string.onboarding_page3_body),
)

@Composable
fun OnboardingScreen(
    onContinue: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pagerState = rememberPagerState(pageCount = { pages.size })
    val scope = rememberCoroutineScope()
    val lastPage = pages.lastIndex

    LaunchedEffect(pagerState.currentPage) {
        Log.i(TAG, "Onboarding page ${pagerState.currentPage + 1} of ${pages.size}")
    }

    BackHandler(enabled = pagerState.currentPage > 0) {
        scope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp),
    ) {
        HorizontalPager(
            state = pagerState,
            modifier = Modifier.weight(1f),
        ) { page ->
            Column(
                modifier = Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
            ) {
                Text(
                    text = stringResource(pages[page].titleRes),
                    style = MaterialTheme.typography.headlineMedium,
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(pages[page].bodyRes),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
        }
        PageDots(current = pagerState.currentPage, count = pages.size)
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = {
                if (pagerState.currentPage < lastPage) {
                    scope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) }
                } else {
                    onContinue()
                }
            },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text(
                text = stringResource(
                    if (pagerState.currentPage == lastPage) {
                        R.string.onboarding_continue
                    } else {
                        R.string.onboarding_next
                    },
                ),
            )
        }
    }
}

@Composable
private fun PageDots(current: Int, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        repeat(count) { index ->
            val description = stringResource(R.string.onboarding_page_dot, index + 1, count)
            val selected = index == current
            Box(
                modifier = Modifier
                    .padding(horizontal = 4.dp)
                    .size(if (selected) 10.dp else 8.dp)
                    .clip(CircleShape)
                    .background(if (selected) PulseTeal else PulseTeal.copy(alpha = 0.35f))
                    .semantics { contentDescription = description },
            )
        }
    }
}

private const val TAG = "MuniPulse"
