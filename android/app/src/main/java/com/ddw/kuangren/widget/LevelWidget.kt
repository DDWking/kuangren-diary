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
import androidx.glance.layout.Column
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ddw.kuangren.MainActivity

class LevelWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { LevelContent(context) }
    }

    @Composable
    private fun LevelContent(context: Context) {
        val open = actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra("open_tab", "panel")
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF05070D)))
                .padding(8.dp)
                .clickable(open),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "LV." + WidgetCache.level(context),
                style = TextStyle(color = WidgetPaint.primary, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            )
            Text(
                text = WidgetCache.title(context),
                style = TextStyle(color = WidgetPaint.muted, fontSize = 8.sp),
                maxLines = 1
            )
            Spacer(modifier = GlanceModifier.height(2.dp))
            Text(
                text = WidgetCache.exp(context).toString() + "/" + WidgetCache.expNext(context) + " EXP",
                style = TextStyle(color = WidgetPaint.secondary, fontSize = 7.sp)
            )
        }
    }
}

class LevelWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = LevelWidget()
}