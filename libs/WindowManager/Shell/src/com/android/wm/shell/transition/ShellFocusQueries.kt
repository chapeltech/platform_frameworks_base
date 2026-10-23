package com.android.wm.shell.transition

import android.app.ActivityManager
import android.view.Display
import android.view.WindowManagerGlobal
import org.wonad.focusproposal.v2.FocusQueryCoordinator2
import org.wonad.focusproposal.v2.ProcessFocusWorker

/** Exactly one coordinator/worker per SystemUI process; never one per Binder incarnation. */
internal object ShellFocusQueries {
    @JvmField val coordinator = FocusQueryCoordinator2(ProcessFocusWorker, ::observeUser)

    private fun observeUser(callerUser: Int): Int {
        // Already-existing permission + exact SystemUI-UID gated WMS method.
        // This blocking Binder call is ONLY executed on ProcessFocusWorker.
        return if (ActivityManager.getCurrentUser() != callerUser) Display.INVALID_DISPLAY else {
            val display = WindowManagerGlobal.getWindowManagerService()?.getWonadFocusedDisplayId()
                ?: Display.INVALID_DISPLAY
            if (ActivityManager.getCurrentUser() == callerUser) display else Display.INVALID_DISPLAY
        }
    }
}
