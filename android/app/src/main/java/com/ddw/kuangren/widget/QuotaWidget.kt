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
import androidx.glance.layout.Box
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

class QuotaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        provideContent { QuotaContent(context) }
    }

    @Composable
    private fun QuotaContent(context: Context) {
        val remaining = WidgetCache.quotaRemaining(context)
        val total = WidgetCache.quotaTotal(context).coerceAtLeast(1)
        val used = WidgetCache.quotaUsed(context).coerceIn(0, total)
        val fraction = (used.toFloat() / total.toFloat()).coerceIn(0f, 1f)
        val open = actionStartActivity(
            Intent(context, MainActivity::class.java).putExtra("open_tab", "panel")
        )
        Column(
            modifier = GlanceModifier
                .fillMaxSize()
                .background(ColorProvider(Color(0xFF05070D)))
                .padding(10.dp)
                .clickable(open),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "娱乐额度",
                style = TextStyle(color = WidgetPaint.muted, fontSize = 9.sp)
            )
            Text(
                text = remaining.toString() + " MIN",
                style = TextStyle(color = WidgetPaint.primary, fontSize = 22.sp, fontWeight = FontWeight.Bold)
            )
            Spacer(modifier = GlanceModifier.height(4.dp))
            val segments = 10
            val filled = (fraction.coerceIn(0f, 1f) * segments).toInt()
            Row(modifier = GlanceModifier.fillMaxWidth().height(4.dp)) {
                repeat(segments) { index ->
                    Spacer(
                        modifier = GlanceModifier
                            .defaultWeight()
                            .height(4.dp)
                            .background(if (index < filled) WidgetPaint.primary else ColorProvider(Color(0xFF111B2E)))
                    )
                }
            }
            Spacer(modifier = GlanceModifier.height(4.dp))
            Text(
                text = WidgetCache.quotaStatusText(context) + " · 已用 " + used + "/" + total,
                style = TextStyle(color = WidgetPaint.muted, fontSize = 8.sp)
            )
        }
    }
}

class QuotaWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = QuotaWidget()
}