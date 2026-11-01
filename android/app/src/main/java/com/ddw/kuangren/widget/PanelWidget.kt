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
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.ddw.kuangren.MainActivity

class PanelWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent {
            PanelContent(context)
        }
    }

    @Composable
    private fun PanelContent(context: Context) {
        val open = actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra("open_tab", "panel")
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF05070D)))
                .padding(10.dp)
                .clickable(open)
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LV." + WidgetCache.level(context) + " " + WidgetCache.title(context),
                    style = TextStyle(color = WidgetPaint.primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "距考研 " + WidgetCache.examDays(context) + " 天",
                    style = TextStyle(color = WidgetPaint.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = WidgetCache.quotaRemaining(context).toString() + " MIN",
                        style = TextStyle(color = WidgetPaint.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = WidgetCache.quotaStatusText(context) + " · 已用 " + WidgetCache.quotaUsed(context),
                        style = TextStyle(color = WidgetPaint.muted, fontSize = 9.sp)
                    )
                }
                Text(
                    text = "任务 " + WidgetCache.tasksDone(context) + "/" + WidgetCache.tasksTotal(context),
                    style = TextStyle(color = WidgetPaint.text, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = GlanceModifier.height(3.dp))
            WidgetCache.taskLines(context).take(2).forEach { line ->
                Text(
                    text = line,
                    style = TextStyle(color = WidgetPaint.muted, fontSize = 9.sp),
                    maxLines = 1
                )
            }
        }
    }
}

class PanelWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = PanelWidget()
}