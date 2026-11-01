package com.ddw.kuangren.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ddw.kuangren.MainActivity

class LineWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { LineContent(context) }
    }

    @Composable
    private fun LineContent(context: Context) {
        val open = actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra("open_tab", "panel")
        )
        Row(
            modifier = GlanceModifier
                .fillMaxSize()
                .fillMaxWidth()
                .background(ColorProvider(Color(0xFF05070D)))
                .padding(horizontal = 12.dp, vertical = 8.dp)
                .clickable(open),
            horizontalAlignment = Alignment.Start,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = WidgetCache.line(context),
                style = TextStyle(color = WidgetPaint.primary, fontSize = 11.sp, fontWeight = FontWeight.Bold),
                maxLines = 2
            )
        }
    }
}

class LineWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LineWidget()
}
