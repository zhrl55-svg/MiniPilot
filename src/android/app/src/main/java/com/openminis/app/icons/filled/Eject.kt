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

public val Icons.Filled.Eject: ImageVector
    get() {
        if (_eject != null) {
            return _eject!!
        }
        _eject = materialIcon(name = "Filled.Eject") {
            materialPath {
                moveTo(5.0f, 17.0f)
                horizontalLineToRelative(14.0f)
                verticalLineToRelative(2.0f)
                lineTo(5.0f, 19.0f)
                close()
                moveTo(12.0f, 5.0f)
                lineTo(5.33f, 15.0f)
                horizontalLineToRelative(13.34f)
                close()
            }
        }
        return _eject!!
    }

private var _eject: ImageVector? = null
