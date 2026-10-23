package com.android.systemui.display.data.repository

import android.view.Display
import com.android.app.displaylib.DisplaysWithDecorationsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import org.wonad.desktop.broker.WonadNativeUi

/** Guards the enabled WM-callback repository WITHOUT altering displaylib.
 * Keep ordinary removal semantics; ownership is rechecked when queued raw
 * states are consumed, and the final WM add guard closes the admission race.
 */
internal class WonadDecorationsRepository(
    upstream: DisplaysWithDecorationsRepository,
    displays: com.android.app.displaylib.DisplayRepository,
    scope: CoroutineScope,
) : DisplaysWithDecorationsRepository {
    private fun filter(ids: Set<Int>, connected: Set<Int>): Set<Int> =
        ids.filterTo(linkedSetOf()) {
            it in connected && (it == Display.DEFAULT_DISPLAY ||
                WonadNativeUi.allowStockDecorations(it))
        }

    override val displayIdsWithSystemDecorations: StateFlow<Set<Int>> =
        combine(upstream.displayIdsWithSystemDecorations, displays.displayIds) { ids, connected ->
            filter(ids, connected)
        }.stateIn(
            scope,
            SharingStarted.Eagerly,
            filter(upstream.displayIdsWithSystemDecorations.value, displays.displayIds.value),
        )
}
