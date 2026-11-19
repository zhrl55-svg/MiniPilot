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
import kotlin.Deprecated

@Deprecated(
    "Use the AutoMirrored version at Icons.AutoMirrored.Filled.AltRoute",
    ReplaceWith( "Icons.AutoMirrored.Filled.AltRoute",
            "androidx.compose.material.icons.automirrored.filled.AltRoute"),
)
public val Icons.Filled.AltRoute: ImageVector
    get() {
        if (_altRoute != null) {
            return _altRoute!!
        }
        _altRoute = materialIcon(name = "Filled.AltRoute") {
            materialPath {
                moveTo(9.78f, 11.16f)
                lineToRelative(-1.42f, 1.42f)
                curveToRelative(-0.68f, -0.69f, -1.34f, -1.58f, -1.79f, -2.94f)
                lineToRelative(1.94f, -0.49f)
                curveTo(8.83f, 10.04f, 9.28f, 10.65f, 9.78f, 11.16f)
                close()
                moveTo(11.0f, 6.0f)
                lineTo(7.0f, 2.0f)
                lineTo(3.0f, 6.0f)
                horizontalLineToRelative(3.02f)
                curveTo(6.04f, 6.81f, 6.1f, 7.54f, 6.21f, 8.17f)
                lineToRelative(1.94f, -0.49f)
                curveTo(8.08f, 7.2f, 8.03f, 6.63f, 8.02f, 6.0f)
                horizontalLineTo(11.0f)
                close()
                moveTo(21.0f, 6.0f)
                lineToRelative(-4.0f, -4.0f)
                lineToRelative(-4.0f, 4.0f)
                horizontalLineToRelative(2.99f)
                curveToRelative(-0.1f, 3.68f, -1.28f, 4.75f, -2.54f, 5.88f)
                curveToRelative(-0.5f, 0.44f, -1.01f, 0.92f, -1.45f, 1.55f)
                curveToRelative(-0.34f, -0.49f, -0.73f, -0.88f, -1.13f, -1.24f)
                lineTo(9.46f, 13.6f)
                curveTo(10.39f, 14.45f, 11.0f, 15.14f, 11.0f, 17.0f)
                curveToRelative(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
                horizontalLineToRelative(0.0f)
                verticalLineToRelative(5.0f)
                horizontalLineToRelative(2.0f)
                verticalLineToRelative(-5.0f)
                curveToRelative(0.0f, 0.0f, 0.0f, 0.0f, 0.0f, 0.0f)
                curveToRelative(0.0f, -2.02f, 0.71f, -2.66f, 1.79f, -3.63f)
                curveToRelative(1.38f, -1.24f, 3.08f, -2.78f, 3.2f, -7.37f)
                horizontalLineTo(21.0f)
                close()
            }
        }
        return _altRoute!!
    }

private var _altRoute: ImageVector? = null
