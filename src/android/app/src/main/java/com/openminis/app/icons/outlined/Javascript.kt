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

public val Icons.Outlined.Javascript: ImageVector
    get() {
        if (_javascript != null) {
            return _javascript!!
        }
        _javascript = materialIcon(name = "Outlined.Javascript") {
            materialPath {
                moveTo(12.0f, 14.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineToRelative(1.5f)
                verticalLineToRelative(0.5f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-1.0f)
                horizontalLineTo(13.0f)
                curveToRelative(-0.55f, 0.0f, -1.0f, -0.45f, -1.0f, -1.0f)
                verticalLineTo(10.0f)
                curveToRelative(0.0f, -0.55f, 0.45f, -1.0f, 1.0f, -1.0f)
                horizontalLineToRelative(3.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineToRelative(1.0f)
                horizontalLineToRelative(-1.5f)
                verticalLineToRelative(-0.5f)
                horizontalLineToRelative(-2.0f)
                verticalLineToRelative(1.0f)
                horizontalLineTo(16.0f)
                curveToRelative(0.55f, 0.0f, 1.0f, 0.45f, 1.0f, 1.0f)
                verticalLineTo(14.0f)
                curveToRelative(0.0f, 0.55f, -0.45f, 1.0f, -1.0f, 1.0f)
                horizontalLineToRelative(-3.0f)
                curveTo(12.45f, 15.0f, 12.0f, 14.55f, 12.0f, 14.0f)
                close()
                moveTo(9.0f, 9.0f)
                verticalLineToRelative(4.5f)
                horizontalLineTo(7.5f)
                verticalLineToRelative(-1.0f)
                horizontalLineTo(6.0f)
                verticalLineToRelative(1.0f)
                curveTo(6.0f, 14.33f, 6.67f, 15.0f, 7.5f, 15.0f)
                horizontalLineTo(9.0f)
                curveToRelative(0.83f, 0.0f, 1.5f, -0.67f, 1.5f, -1.5f)
                verticalLineTo(9.0f)
                curveTo(10.5f, 9.0f, 9.83f, 9.0f, 9.0f, 9.0f)
                close()
            }
        }
        return _javascript!!
    }

private var _javascript: ImageVector? = null
