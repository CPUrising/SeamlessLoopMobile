package com.cpu.seamlessloopmobile.ui.components.common

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.cpu.seamlessloopmobile.ui.theme.SeamlessLoopPlayerColors

/**
 * 循环点手动数值编辑弹窗喵！(๑•̀ㅂ•́)و✧
 * 从原 FineTuneComponents 优雅抽离，并已完美消除硬编码颜色。
 */
@Composable
fun LoopEditDialog(
    visible: Boolean,
    isStart: Boolean,
    samplesValue: String,
    timeValue: String,
    onValueSamplesChange: (String) -> Unit,
    onValueTimeChange: (String) -> Unit,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    if (visible) {
        val textFieldColors = OutlinedTextFieldDefaults.colors(
            focusedTextColor = SeamlessLoopPlayerColors.PrimaryText,
            unfocusedTextColor = SeamlessLoopPlayerColors.PrimaryText,
            disabledTextColor = SeamlessLoopPlayerColors.TertiaryText,
            errorTextColor = SeamlessLoopPlayerColors.OnErrorContainer,
            focusedContainerColor = Color.Transparent,
            unfocusedContainerColor = Color.Transparent,
            disabledContainerColor = Color.Transparent,
            errorContainerColor = Color.Transparent,
            cursorColor = SeamlessLoopPlayerColors.Primary,
            errorCursorColor = SeamlessLoopPlayerColors.OnErrorContainer,
            focusedBorderColor = SeamlessLoopPlayerColors.Primary,
            unfocusedBorderColor = SeamlessLoopPlayerColors.Track,
            disabledBorderColor = SeamlessLoopPlayerColors.Inactive.copy(alpha = 0.5f),
            errorBorderColor = SeamlessLoopPlayerColors.OnErrorContainer,
            focusedLabelColor = SeamlessLoopPlayerColors.Primary,
            unfocusedLabelColor = SeamlessLoopPlayerColors.SecondaryText,
            disabledLabelColor = SeamlessLoopPlayerColors.TertiaryText,
            errorLabelColor = SeamlessLoopPlayerColors.OnErrorContainer
        )
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { 
                Text(
                    text = if (isStart) "修改循环起点 (A)" else "修改循环终点 (B)", 
                    color = SeamlessLoopPlayerColors.PrimaryText,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                ) 
            },
            text = {
                Column {
                    OutlinedTextField(
                        value = samplesValue,
                        onValueChange = onValueSamplesChange,
                        label = { Text("采样数 (Samples)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors
                    )
                    Spacer(modifier = Modifier.height(12.dp))
                    OutlinedTextField(
                        value = timeValue,
                        onValueChange = onValueTimeChange,
                        label = { Text("时间 (Seconds)") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = onConfirm) {
                    Text("确定", color = SeamlessLoopPlayerColors.Primary, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("取消", color = SeamlessLoopPlayerColors.TertiaryText)
                }
            },
            containerColor = SeamlessLoopPlayerColors.Panel,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
