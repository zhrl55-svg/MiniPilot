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

public val Icons.Outlined.Troubleshoot: ImageVector
    get() {
        if (_troubleshoot != null) {
            return _troubleshoot!!
        }
        _troubleshoot = materialIcon(name = "Outlined.Troubleshoot") {
            materialPath {
                moveTo(22.0f, 20.59f)
                lineToRelative(-4.69f, -4.69f)
                curveTo(18.37f, 14.55f, 19.0f, 12.85f, 19.0f, 11.0f)
                curveToRelative(0.0f, -4.42f, -3.58f, -8.0f, -8.0f, -8.0f)
                curveToRelative(-4.08f, 0.0f, -7.44f, 3.05f, -7.93f, 7.0f)
                horizontalLineToRelative(2.02f)
                curveTo(5.57f, 7.17f, 8.03f, 5.0f, 11.0f, 5.0f)
                curveToRelative(3.31f, 0.0f, 6.0f, 2.69f, 6.0f, 6.0f)
                reflectiveCurveToRelative(-2.69f, 6.0f, -6.0f, 6.0f)
                curveToRelative(-2.42f, 0.0f, -4.5f, -1.44f, -5.45f, -3.5f)
                horizontalLineTo(3.4f)
                curveTo(4.45f, 16.69f, 7.46f, 19.0f, 11.0f, 19.0f)
                curveToRelative(1.85f, 0.0f, 3.55f, -0.63f, 4.9f, -1.69f)
                lineTo(20.59f, 22.0f)
                lineTo(22.0f, 20.59f)
                close()
            }
            materialPath {
                moveTo(8.43f, 9.69f)
                lineToRelative(1.22f, 5.31f)
                lineToRelative(1.64f, 0.0f)
                lineToRelative(1.26f, -3.78f)
                lineToRelative(0.95f, 2.28f)
                lineToRelative(2.0f, 0.0f)
                lineToRelative(0.0f, -1.5f)
                lineToRelative(-1.0f, 0.0f)
                lineToRelative(-1.25f, -3.0f)
                lineToRelative(-1.54f, 0.0f)
                lineToRelative(-1.12f, 3.37f)
                lineToRelative(-1.24f, -5.37f)
                lineToRelative(-1.65f, 0.0f)
                lineToRelative(-1.25f, 4.0f)
                lineToRelative(-5.45f, 0.0f)
                lineToRelative(0.0f, 1.5f)
                lineToRelative(6.55f, 0.0f)
                close()
            }
        }
        return _troubleshoot!!
    }

private var _troubleshoot: ImageVector? = null
