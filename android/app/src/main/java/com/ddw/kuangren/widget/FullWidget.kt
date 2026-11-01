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

class FullWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { FullContent(context) }
    }

    @Composable
    private fun FullContent(context: Context) {
        val open = actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra("open_tab", "panel")
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF05070D)))
                .padding(12.dp)
                .clickable(open)
        ) {
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(
                    text = "LV." + WidgetCache.level(context) + " " + WidgetCache.title(context),
                    style = TextStyle(color = WidgetPaint.primary, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                )
                Spacer(modifier = GlanceModifier.defaultWeight())
                Text(
                    text = "距考研 " + WidgetCache.examDays(context) + " 天",
                    style = TextStyle(color = WidgetPaint.secondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = GlanceModifier.height(6.dp))
            val attrs = WidgetCache.attrs(context)
            val labels = listOf("智", "体", "意", "专", "秩")
            Row(modifier = GlanceModifier.fillMaxWidth()) {
                attrs.forEachIndexed { index, value ->
                    Text(
                        text = labels[index] + " " + value,
                        style = TextStyle(color = WidgetPaint.muted, fontSize = 9.sp),
                        modifier = GlanceModifier.defaultWeight()
                    )
                }
            }
            Spacer(modifier = GlanceModifier.height(8.dp))
            Row(modifier = GlanceModifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(modifier = GlanceModifier.defaultWeight()) {
                    Text(
                        text = WidgetCache.quotaRemaining(context).toString() + " MIN",
                        style = TextStyle(color = WidgetPaint.primary, fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    )
                    Text(
                        text = WidgetCache.quotaStatusText(context) + " · 基础 " + WidgetCache.quotaTotal(context),
                        style = TextStyle(color = WidgetPaint.muted, fontSize = 9.sp)
                    )
                }
                Text(
                    text = "任务 " + WidgetCache.tasksDone(context) + "/" + WidgetCache.tasksTotal(context),
                    style = TextStyle(color = WidgetPaint.text, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                )
            }
            Spacer(modifier = GlanceModifier.height(6.dp))
            WidgetCache.taskLines(context).forEach { line ->
                Text(
                    text = line,
                    style = TextStyle(color = WidgetPaint.text, fontSize = 10.sp),
                    maxLines = 1
                )
            }
            Spacer(modifier = GlanceModifier.defaultWeight())
            Text(
                text = "更新于 " + WidgetCache.updatedAt(context),
                style = TextStyle(color = WidgetPaint.muted, fontSize = 8.sp)
            )
        }
    }
}

class FullWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = FullWidget()
}