/*
 * Copyright 2025 The Android Open Source Project
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

package com.openminis.app.icons.filled

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Filled.CloseFullscreen: ImageVector
    get() {
        if (_closeFullscreen != null) {
            return _closeFullscreen!!
        }
        _closeFullscreen = materialIcon(name = "Filled.CloseFullscreen") {
            materialPath {
                moveTo(22.0f, 3.41f)
                lineToRelative(-5.29f, 5.29f)
                lineTo(20.0f, 12.0f)
                horizontalLineToRelative(-8.0f)
                verticalLineTo(4.0f)
                lineToRelative(3.29f, 3.29f)
                lineTo(20.59f, 2.0f)
                lineTo(22.0f, 3.41f)
                close()
                moveTo(3.41f, 22.0f)
                lineToRelative(5.29f, -5.29f)
                lineTo(12.0f, 20.0f)
                verticalLineToRelative(-8.0f)
                horizontalLineTo(4.0f)
                lineToRelative(3.29f, 3.29f)
                lineTo(2.0f, 20.59f)
                lineTo(3.41f, 22.0f)
                close()
            }
        }
        return _closeFullscreen!!
    }

private var _closeFullscreen: ImageVector? = null
