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

public val Icons.Outlined.Handyman: ImageVector
    get() {
        if (_handyman != null) {
            return _handyman!!
        }
        _handyman = materialIcon(name = "Outlined.Handyman") {
            materialPath {
                moveTo(21.67f, 18.17f)
                lineToRelative(-5.3f, -5.3f)
                horizontalLineToRelative(-0.99f)
                lineToRelative(-2.54f, 2.54f)
                verticalLineToRelative(0.99f)
                lineToRelative(5.3f, 5.3f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineToRelative(2.12f, -2.12f)
                curveTo(22.06f, 19.2f, 22.06f, 18.56f, 21.67f, 18.17f)
                close()
                moveTo(18.84f, 19.59f)
                lineToRelative(-4.24f, -4.24f)
                lineToRelative(0.71f, -0.71f)
                lineToRelative(4.24f, 4.24f)
                lineTo(18.84f, 19.59f)
                close()
            }
            materialPath {
                moveTo(17.34f, 10.19f)
                lineToRelative(1.41f, -1.41f)
                lineToRelative(2.12f, 2.12f)
                curveToRelative(1.17f, -1.17f, 1.17f, -3.07f, 0.0f, -4.24f)
                lineToRelative(-3.54f, -3.54f)
                lineToRelative(-1.41f, 1.41f)
                verticalLineTo(1.71f)
                lineTo(15.22f, 1.0f)
                lineToRelative(-3.54f, 3.54f)
                lineToRelative(0.71f, 0.71f)
                horizontalLineToRelative(2.83f)
                lineToRelative(-1.41f, 1.41f)
                lineToRelative(1.06f, 1.06f)
                lineToRelative(-2.89f, 2.89f)
                lineTo(7.85f, 6.48f)
                verticalLineTo(5.06f)
                lineTo(4.83f, 2.04f)
                lineTo(2.0f, 4.87f)
                lineToRelative(3.03f, 3.03f)
                horizontalLineToRelative(1.41f)
                lineToRelative(4.13f, 4.13f)
                lineToRelative(-0.85f, 0.85f)
                horizontalLineTo(7.6f)
                lineToRelative(-5.3f, 5.3f)
                curveToRelative(-0.39f, 0.39f, -0.39f, 1.02f, 0.0f, 1.41f)
                lineToRelative(2.12f, 2.12f)
                curveToRelative(0.39f, 0.39f, 1.02f, 0.39f, 1.41f, 0.0f)
                lineToRelative(5.3f, -5.3f)
                verticalLineToRelative(-2.12f)
                lineToRelative(5.15f, -5.15f)
                lineTo(17.34f, 10.19f)
                close()
                moveTo(9.36f, 15.34f)
                lineToRelative(-4.24f, 4.24f)
                lineToRelative(-0.71f, -0.71f)
                lineToRelative(4.24f, -4.24f)
                lineToRelative(0.0f, 0.0f)
                lineTo(9.36f, 15.34f)
                lineTo(9.36f, 15.34f)
                close()
            }
        }
        return _handyman!!
    }

private var _handyman: ImageVector? = null
