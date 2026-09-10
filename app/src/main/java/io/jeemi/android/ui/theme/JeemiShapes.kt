package io.jeemi.android.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Shared small corners; switches, segmented controls and navigation indicators keep their pill shape. */
object JeemiShapes {
    val Panel = RoundedCornerShape(8.dp)
    val Component = RoundedCornerShape(6.dp)
    val Node = RoundedCornerShape(5.dp)
    val Badge = RoundedCornerShape(2.dp)
    val Material = Shapes(extraSmall = Component, small = Component, medium = Component,
        large = Panel, extraLarge = Panel)
}
