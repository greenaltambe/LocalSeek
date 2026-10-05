package com.augt.localseek.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/**
 * "Slightly rounded" shape scale (owner decision): 6 / 8 / 12 / 16 / 20 dp. Buttons, chips, cards, the search bar,
 * dialogs and menus all read these roles. CircleShape is reserved for avatars and the mascot.
 */
val ExpressiveShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(20.dp)
)
