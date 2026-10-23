/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.wm.shell.desktopmode.desktopwallpaperactivity

import android.os.RemoteException
import android.view.Display.DEFAULT_DISPLAY
import android.view.WindowManagerGlobal
import com.android.window.flags.Flags
import com.android.wm.shell.desktopmode.ShellDesktopState

/** Utility methods for the DesktopWallpaperActivity. */
class DesktopWallpaperActivityUtils(private val desktopState: ShellDesktopState) {
    /** Determines if the display will show the DesktopWallpaperActivity, hiding the home screen. */
    fun hasDesktopWallpaperActivityEnabled(displayId: Int): Boolean {
        // A Wonad-owned external screen uses its own secondary home. Do not
        // insert Android's fullscreen desktop wallpaper task above that home.
        // This also respects normal non-decorated external displays.
        if (displayId != DEFAULT_DISPLAY) {
            val decorated = try {
                WindowManagerGlobal.getWindowManagerService()?.shouldShowSystemDecors(displayId) == true
            } catch (_: RemoteException) {
                false
            }
            if (!decorated) return false
        }
        return (displayId == DEFAULT_DISPLAY || Flags.enablePerDisplayDesktopWallpaperActivity()) &&
            Flags.enableDesktopWindowingWallpaperActivity() &&
            !desktopState.shouldShowHomeBehindDesktop
    }
}
