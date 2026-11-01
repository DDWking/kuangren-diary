package com.ddw.kuangren.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.GlanceModifier
import androidx.glance.background
import androidx.glance.unit.ColorProvider

internal object WidgetPaint {
    val background = ColorProvider(Color(0xFF05070D))
    val surface = ColorProvider(Color(0xFF0B1220))
    val primary = ColorProvider(Color(0xFF38F2E8))
    val secondary = ColorProvider(Color(0xFF8B5CF6))
    val text = ColorProvider(Color(0xFFE8F1FF))
    val muted = ColorProvider(Color(0xFF7C8CA6))
    val danger = ColorProvider(Color(0xFFFF4D5E))
    val warning = ColorProvider(Color(0xFFFFB020))
    val success = ColorProvider(Color(0xFF4ADE80))

    fun base(modifier: GlanceModifier = GlanceModifier): GlanceModifier =
        modifier.background(background)
}