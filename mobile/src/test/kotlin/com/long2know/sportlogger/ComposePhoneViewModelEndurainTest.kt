package com.long2know.sportlogger

import androidx.lifecycle.SavedStateHandle
import com.long2know.sportlogger.publishing.endurain.EndurainDeliveryKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class ComposePhoneViewModelEndurainTest {
    private val deliveryKey = EndurainDeliveryKey(
        "f37dca54-d36f-4de2-b62e-b36e8d3f32b2",
        1L,
        "https://endurain.example",
        "connection",
    )

    @Test
    fun mixedInvalidRemoteIdsRemainEditableWithoutStartingReconciliation() {
        val invalidValues = listOf(
            "41,bad,-2",
            "41,0",
            "41,-2",
            "41,9223372036854775808",
        )

        invalidValues.forEach { value ->
            val viewModel = ComposePhoneViewModel(SavedStateHandle())
            viewModel.openEndurainReconcile(deliveryKey)
            viewModel.updateEndurainRemoteIds(value)

            viewModel.reconcileEndurainRemoteIds()

            val reconcile = checkNotNull(viewModel.state.value.endurainReconcile)
            assertEquals(value, reconcile.remoteIds)
            assertFalse(reconcile.saving)
            assertEquals(
                "Each remote ID must be a comma-separated positive integer " +
                    "between 1 and ${Long.MAX_VALUE}.",
                reconcile.error,
            )
        }
    }

    @Test
    fun blankRemoteIdListIsRejectedWithoutClosingDialog() {
        val viewModel = ComposePhoneViewModel(SavedStateHandle())
        viewModel.openEndurainReconcile(deliveryKey)
        viewModel.updateEndurainRemoteIds(" ,  , ")

        viewModel.reconcileEndurainRemoteIds()

        val reconcile = checkNotNull(viewModel.state.value.endurainReconcile)
        assertFalse(reconcile.saving)
        assertEquals("Enter at least one positive remote ID.", reconcile.error)
    }

    @Test
    fun validRemoteIdsPreserveTheCompleteDistinctInput() {
        assertEquals(
            listOf(41L, 42L, Long.MAX_VALUE),
            ComposePhoneViewModel.parseEndurainRemoteIds(
                " 41, ,42,41,${Long.MAX_VALUE} ",
            ),
        )
    }
}
