package tw.myfsl.app.core.data

import tw.myfsl.app.core.model.FinanceSnapshot
import tw.myfsl.app.core.model.Scenario

/** 同一份資料庫內容產生的快照與情境；[scenarios] 屬於 [snapshot] 的世代。 */
data class SnapshotWithScenarios(val snapshot: FinanceSnapshot, val scenarios: List<Scenario>)
