package com.ddw.kuangren.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.ddw.kuangren.ui.theme.Holo

@Composable
fun HoloInput(
    value: String,
    onValueChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    singleLine: Boolean = true,
    keyboardType: KeyboardType = KeyboardType.Text,
    maxLines: Int = if (singleLine) 1 else 8,
    minHeight: Dp = 0.dp,
    enabled: Boolean = true
) {
    BasicTextField(
        value = value,
        onValueChange = onValueChange,
        modifier = modifier
            .fillMaxWidth()
            .heightIn(min = minHeight)
            .background(Holo.SurfaceAlt, RoundedCornerShape(4.dp))
            .border(1.dp, Holo.Border, RoundedCornerShape(4.dp))
            .padding(horizontal = 10.dp, vertical = 9.dp),
        enabled = enabled,
        textStyle = TextStyle(
            color = Holo.Text,
            fontSize = 13.sp,
            fontFamily = FontFamily.Monospace
        ),
        cursorBrush = SolidColor(Holo.Primary),
        singleLine = singleLine,
        maxLines = maxLines,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        decorationBox = { innerTextField ->
            Box {
                if (value.isEmpty() && placeholder.isNotEmpty()) {
                    Text(text = placeholder, color = Holo.TextMuted.copy(alpha = 0.8f), fontSize = 12.sp)
                }
                innerTextField()
            }
        }
    )
}

@Composable
fun HoloSwitch(checked: Boolean, onCheckedChange: (Boolean) -> Unit) {
    Switch(
        checked = checked,
        onCheckedChange = onCheckedChange,
        colors = SwitchDefaults.colors(
            checkedThumbColor = Color(0xFF05070D),
            checkedTrackColor = Holo.Primary,
            checkedBorderColor = Holo.Primary,
            uncheckedThumbColor = Holo.TextMuted,
            uncheckedTrackColor = Holo.SurfaceAlt,
            uncheckedBorderColor = Holo.Border
        )
    )
}

@Composable
fun HoloDialog(
    title: String,
    onDismiss: () -> Unit,
    confirmText: String = "确定",
    confirmEnabled: Boolean = true,
    confirmColor: Color = Holo.Primary,
    onConfirm: () -> Unit,
    dismissText: String = "取消",
    content: @Composable ColumnScope.() -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        HoloCard(active = true, padding = 16.dp) {
            Text(text = title, color = Holo.Text, fontSize = 15.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            content()
            Spacer(Modifier.height(16.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
                HoloButton(
                    text = dismissText,
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f),
                    filled = false,
                    color = Holo.TextMuted
                )
                HoloButton(
                    text = confirmText,
                    onClick = onConfirm,
                    modifier = Modifier.weight(1f),
                    filled = true,
                    color = confirmColor,
                    enabled = confirmEnabled
                )
            }
        }
    }
}

@Composable
fun CircleCheck(done: Boolean, onClick: () -> Unit, size: Dp = 28.dp) {
    Box(
        modifier = Modifier
            .size(size)
            .clip(CircleShape)
            .background(if (done) Holo.Primary.copy(alpha = 0.22f) else Color.Transparent)
            .border(1.dp, if (done) Holo.Primary else Holo.Border, CircleShape)
            .clickableNoRipple(onClick),
        contentAlignment = Alignment.Center
    ) {
        if (done) {
            Text(text = "✓", color = Holo.Primary, fontSize = 13.sp, fontWeight = FontWeight.Bold)
        } else {
            Box(
                modifier = Modifier
                    .size(size * 0.34f)
                    .clip(CircleShape)
                    .background(Holo.TextMuted.copy(alpha = 0.35f))
            )
        }
    }
}

@Composable
fun FieldLabel(text: String) {
    Text(
        text = text,
        color = Holo.TextMuted,
        fontSize = 11.sp,
        modifier = Modifier.padding(bottom = 5.dp)
    )
}

@Composable
fun KeyValueRow(key: String, value: String, valueColor: Color = Holo.Text) {
    Row(modifier = Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text = key, color = Holo.TextMuted, fontSize = 12.sp, modifier = Modifier.weight(1f))
        Text(text = value, color = valueColor, fontSize = 12.sp, fontFamily = FontFamily.Monospace, textAlign = TextAlign.End)
    }
}

fun Modifier.clickableNoRipple(onClick: () -> Unit): Modifier = this.clickable(
    interactionSource = null,
    indication = null,
    onClick = onClick
)
