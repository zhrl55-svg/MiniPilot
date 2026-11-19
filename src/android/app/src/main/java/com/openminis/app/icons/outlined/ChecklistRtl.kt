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

package com.openminis.app.icons.outlined

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.materialIcon
import androidx.compose.material.icons.materialPath
import androidx.compose.ui.graphics.vector.ImageVector

public val Icons.Outlined.ChecklistRtl: ImageVector
    get() {
        if (_checklistRtl != null) {
            return _checklistRtl!!
        }
        _checklistRtl = materialIcon(name = "Outlined.ChecklistRtl") {
            materialPath {
                moveTo(11.0f, 7.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(9.0f)
                verticalLineTo(7.0f)
                close()
                moveTo(11.0f, 15.0f)
                horizontalLineTo(2.0f)
                verticalLineToRelative(2.0f)
                horizontalLineToRelative(9.0f)
                verticalLineTo(15.0f)
                close()
                moveTo(16.34f, 11.0f)
                lineToRelative(-3.54f, -3.54f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(2.12f, 2.12f)
                lineToRelative(4.24f, -4.24f)
                lineTo(22.0f, 5.34f)
                lineTo(16.34f, 11.0f)
                close()
                moveTo(16.34f, 19.0f)
                lineToRelative(-3.54f, -3.54f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(2.12f, 2.12f)
                lineToRelative(4.24f, -4.24f)
                lineTo(22.0f, 13.34f)
                lineTo(16.34f, 19.0f)
                close()
            }
        }
        return _checklistRtl!!
    }

private var _checklistRtl: ImageVector? = null
