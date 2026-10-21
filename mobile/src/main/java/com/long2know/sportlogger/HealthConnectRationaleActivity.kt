package com.long2know.sportlogger

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.long2know.sportlogger.ui.SportLoggerTheme

class HealthConnectRationaleActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            SportLoggerTheme {
                HealthConnectRationaleScreen(onDone = ::finish)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HealthConnectRationaleScreen(onDone: () -> Unit) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.hc_rationale_title)) },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(PaddingValues(20.dp)),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            RationaleSection(
                title = stringResource(R.string.hc_rationale_control_title),
                body = stringResource(R.string.hc_rationale_control_body),
            )
            RationaleSection(
                title = stringResource(R.string.hc_rationale_write_title),
                body = stringResource(R.string.hc_rationale_write_body),
            )
            RationaleSection(
                title = stringResource(R.string.hc_rationale_read_title),
                body = stringResource(R.string.hc_rationale_read_body),
            )
            RationaleSection(
                title = stringResource(R.string.hc_rationale_delete_title),
                body = stringResource(R.string.hc_rationale_delete_body),
            )
            RationaleSection(
                title = stringResource(R.string.hc_rationale_security_title),
                body = stringResource(R.string.hc_rationale_security_body),
            )
            Button(
                onClick = onDone,
                modifier = Modifier.fillMaxWidth(),
            ) {
                Text(stringResource(R.string.done))
            }
        }
    }
}

@Composable
private fun RationaleSection(
    title: String,
    body: String,
) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(
            text = title,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.semantics { heading() },
        )
        Text(body)
    }
}
