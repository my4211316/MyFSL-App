package tw.myfsl.app.core.data

import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import tw.myfsl.app.core.model.FinanceSnapshot

class ReviewBoundaryTest {
    @Test fun inconsistentPersistentGenerationsMustBlockEvenUnversionedWrites() = runBlocking {
        val gate = WriteGate(Mutex(), { RestoreState.READY }, { FinanceSnapshot.NO_GENERATION })
        var wrote = false
        try {
            gate.run(null) { wrote = true }
        } catch (_: WriteRejectedException) {}
        assertFalse("Database/settings generation mismatch must block writes even when expected is null", wrote)
    }
}
