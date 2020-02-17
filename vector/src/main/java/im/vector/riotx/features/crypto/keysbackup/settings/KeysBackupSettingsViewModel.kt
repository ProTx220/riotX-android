/*
 * Copyright 2019 New Vector Ltd
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package im.vector.riotx.features.crypto.keysbackup.settings

import com.airbnb.mvrx.*
import com.squareup.inject.assisted.Assisted
import com.squareup.inject.assisted.AssistedInject
import im.vector.matrix.android.api.MatrixCallback
import im.vector.matrix.android.api.NoOpMatrixCallback
import im.vector.matrix.android.api.session.Session
import im.vector.matrix.android.api.session.crypto.keysbackup.KeysBackupService
import im.vector.matrix.android.api.session.crypto.keysbackup.KeysBackupState
import im.vector.matrix.android.api.session.crypto.keysbackup.KeysBackupStateListener
import im.vector.matrix.android.internal.crypto.keysbackup.model.KeysBackupVersionTrust
import im.vector.riotx.core.platform.EmptyViewEvents
import im.vector.riotx.core.platform.VectorViewModel

class KeysBackupSettingsViewModel @AssistedInject constructor(@Assisted initialState: KeysBackupSettingViewState,
                                                              session: Session
) : VectorViewModel<KeysBackupSettingViewState, KeyBackupSettingsAction, EmptyViewEvents>(initialState),
        KeysBackupStateListener {

    @AssistedInject.Factory
    interface Factory {
        fun create(initialState: KeysBackupSettingViewState): KeysBackupSettingsViewModel
    }

    companion object : MvRxViewModelFactory<KeysBackupSettingsViewModel, KeysBackupSettingViewState> {

        @JvmStatic
        override fun create(viewModelContext: ViewModelContext, state: KeysBackupSettingViewState): KeysBackupSettingsViewModel? {
            val activity: KeysBackupManageActivity = (viewModelContext as ActivityViewModelContext).activity()
            return activity.keysBackupSettingsViewModelFactory.create(state)
        }
    }

    private val keysBackupService: KeysBackupService = session.cryptoService().keysBackupService()

    init {
        setState {
            this.copy(
                    keysBackupState = keysBackupService.state,
                    keysBackupVersion = keysBackupService.keysBackupVersion
            )
        }
        keysBackupService.addListener(this)
        getKeysBackupTrust()
    }

    override fun handle(action: KeyBackupSettingsAction) {
        when (action) {
            KeyBackupSettingsAction.Init              -> init()
            KeyBackupSettingsAction.GetKeyBackupTrust -> getKeysBackupTrust()
            KeyBackupSettingsAction.DeleteKeyBackup   -> deleteCurrentBackup()
        }
    }

    private fun init() {
        keysBackupService.forceUsingLastVersion(NoOpMatrixCallback())
    }

    private fun getKeysBackupTrust() = withState { state ->
        val versionResult = keysBackupService.keysBackupVersion

        if (state.keysBackupVersionTrust is Uninitialized && versionResult != null) {
            setState {
                copy(
                        keysBackupVersionTrust = Loading(),
                        deleteBackupRequest = Uninitialized
                )
            }

            keysBackupService
                    .getKeysBackupTrust(versionResult, object : MatrixCallback<KeysBackupVersionTrust> {
                        override fun onSuccess(data: KeysBackupVersionTrust) {
                            setState {
                                copy(
                                        keysBackupVersionTrust = Success(data)
                                )
                            }
                        }

                        override fun onFailure(failure: Throwable) {
                            setState {
                                copy(
                                        keysBackupVersionTrust = Fail(failure)
                                )
                            }
                        }
                    })
        }
    }

    override fun onCleared() {
        keysBackupService.removeListener(this)
        super.onCleared()
    }

    override fun onStateChange(newState: KeysBackupState) {
        setState {
            copy(
                    keysBackupState = newState,
                    keysBackupVersion = keysBackupService.keysBackupVersion
            )
        }

        getKeysBackupTrust()
    }

    private fun deleteCurrentBackup() {
        val keysBackupService = keysBackupService

        if (keysBackupService.currentBackupVersion != null) {
            setState {
                copy(
                        deleteBackupRequest = Loading()
                )
            }

            keysBackupService.deleteBackup(keysBackupService.currentBackupVersion!!, object : MatrixCallback<Unit> {
                override fun onSuccess(data: Unit) {
                    setState {
                        copy(
                                keysBackupVersion = null,
                                keysBackupVersionTrust = Uninitialized,
                                // We do not care about the success data
                                deleteBackupRequest = Uninitialized
                        )
                    }
                }

                override fun onFailure(failure: Throwable) {
                    setState {
                        copy(
                                deleteBackupRequest = Fail(failure)
                        )
                    }
                }
            })
        }
    }

    fun canExit(): Boolean {
        val currentBackupState = keysBackupService.state

        return currentBackupState == KeysBackupState.Unknown
                || currentBackupState == KeysBackupState.CheckingBackUpOnHomeserver
    }
}
