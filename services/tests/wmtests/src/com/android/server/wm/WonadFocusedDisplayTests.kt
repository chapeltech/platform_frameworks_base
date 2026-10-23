/*
 * Copyright (C) 2026 The Android Open Source Project
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy at http://www.apache.org/licenses/LICENSE-2.0
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.server.wm

import android.os.Binder
import android.os.UserHandle
import android.platform.test.annotations.Presubmit
import androidx.test.filters.SmallTest
import com.android.dx.mockito.inline.extended.ExtendedMockito.doNothing
import com.android.dx.mockito.inline.extended.ExtendedMockito.doReturn
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.doThrow
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify

/** Run with atest WmTests:WonadFocusedDisplayTests. This tests the real bridge. */
@SmallTest
@Presubmit
@RunWith(WindowTestRunner::class)
class WonadFocusedDisplayTests : WindowTestsBase() {
    private val permission = "android.permission.STATUS_BAR_SERVICE"
    private val reason = "Wonad focus observation"
    private val systemUi = "com.android.systemui"

    @Before
    fun allowActualCallerAsSystemUi() {
        doNothing().`when`(mWm.mContext).enforceCallingPermission(permission, reason)
        val packages = mWm.mContext.packageManager
        doReturn(Binder.getCallingUid()).`when`(packages)
            .getPackageUidAsUser(systemUi, UserHandle.USER_SYSTEM)
        clearInvocations(mWm.mRoot)
    }

    @Test
    fun permissionDeniedDoesNotReadFocusEvenForMatchingSystemUiUid() {
        doThrow(SecurityException("denied")).`when`(mWm.mContext)
            .enforceCallingPermission(permission, reason)
        assertThrows(SecurityException::class.java) { mWm.getWonadFocusedDisplayId() }
        verify(mWm.mRoot, never()).getTopFocusedDisplayContent()
    }

    @Test
    fun permissionAloneDoesNotAuthorizeAnotherUid() {
        val packages = mWm.mContext.packageManager
        doReturn(Binder.getCallingUid() + 1).`when`(packages)
            .getPackageUidAsUser(systemUi, UserHandle.USER_SYSTEM)
        assertThrows(SecurityException::class.java) { mWm.getWonadFocusedDisplayId() }
        verify(mWm.mRoot, never()).getTopFocusedDisplayContent()
    }

    @Test
    fun sameAppIdInAnotherUserIsNotSystemUiOwner() {
        val packages = mWm.mContext.packageManager
        doReturn(Binder.getCallingUid() + UserHandle.PER_USER_RANGE)
            .`when`(packages)
            .getPackageUidAsUser(systemUi, UserHandle.USER_SYSTEM)
        assertThrows(SecurityException::class.java) { mWm.getWonadFocusedDisplayId() }
        verify(mWm.mRoot, never()).getTopFocusedDisplayContent()
    }

    @Test
    fun authorizedQueryReadsLiveWmsFocusIncludingEmptyDesktopAndPhone() {
        val external = mock(DisplayContent::class.java)
        doReturn(7).`when`(external).getDisplayId()
        doReturn(external).`when`(mWm.mRoot).getTopFocusedDisplayContent()
        assertEquals(7, mWm.getWonadFocusedDisplayId())
        // No transition, task token, focus lease or native input grant occurs.
        doReturn(mDefaultDisplay).`when`(mWm.mRoot).getTopFocusedDisplayContent()
        assertEquals(0, mWm.getWonadFocusedDisplayId())
        verify(mWm.mContext.packageManager, org.mockito.Mockito.atLeastOnce())
            .getPackageUidAsUser(systemUi, UserHandle.USER_SYSTEM)
    }

    @Test
    fun failedObservationDoesNotChangeCallingIdentity() {
        val before = Binder.getCallingUid()
        doThrow(IllegalStateException("test root failure")).`when`(mWm.mRoot)
            .getTopFocusedDisplayContent()
        assertThrows(IllegalStateException::class.java) { mWm.getWonadFocusedDisplayId() }
        assertEquals(before, Binder.getCallingUid())
    }
}
