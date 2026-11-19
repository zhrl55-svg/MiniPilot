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

public val Icons.Filled.Terrain: ImageVector
    get() {
        if (_terrain != null) {
            return _terrain!!
        }
        _terrain = materialIcon(name = "Filled.Terrain") {
            materialPath {
                moveTo(14.0f, 6.0f)
                lineToRelative(-3.75f, 5.0f)
                lineToRelative(2.85f, 3.8f)
                lineToRelative(-1.6f, 1.2f)
                curveTo(9.81f, 13.75f, 7.0f, 10.0f, 7.0f, 10.0f)
                lineToRelative(-6.0f, 8.0f)
                horizontalLineToRelative(22.0f)
                lineTo(14.0f, 6.0f)
                close()
            }
        }
        return _terrain!!
    }

private var _terrain: ImageVector? = null
