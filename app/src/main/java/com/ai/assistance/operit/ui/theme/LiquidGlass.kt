package com.ai.assistance.operit.ui.theme

import android.os.Build
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.shape.CornerBasedShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

// backdrop库使用Java 21编译,与项目Java 17不兼容,暂时禁用
// import com.kyant.backdrop.Backdrop
// import com.kyant.backdrop.drawBackdrop
// import com.kyant.backdrop.effects.blur
// import com.kyant.backdrop.effects.lens
// import com.kyant.backdrop.effects.vibrancy
// import com.kyant.backdrop.highlight.Highlight
// import com.kyant.backdrop.shadow.Shadow

val LocalLiquidGlassBackdrop = compositionLocalOf<Any?> { null }

private const val LiquidGlassMinApi = Build.VERSION_CODES.TIRAMISU

fun isLiquidGlassSupported(): Boolean = Build.VERSION.SDK_INT >= LiquidGlassMinApi

@Composable
fun Modifier.liquidGlass(
    enabled: Boolean,
    shape: CornerBasedShape = RoundedCornerShape(0.dp),
    containerColor: Color,
    shadowElevation: Dp = 14.dp,
    borderWidth: Dp = 1.dp,
    blurRadius: Dp = 10.dp,
    overlayAlphaBoost: Float = 0f,
    enableLens: Boolean = true,
): Modifier {
    if (!enabled) {
        return this
    }

    // backdrop库暂时禁用,使用回退方案
    val backdrop: Any? = null
    val isLightGlass = containerColor.luminance() >= 0.5f
    
    val fallbackBorderColor =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.28f)
        } else {
            Color.White.copy(alpha = 0.16f)
        }
    val fallbackShadow = shadowElevation.coerceAtLeast(10.dp)
    val fallbackSurfaceTint =
        if (isLightGlass) {
            containerColor.copy(alpha = 0.16f)
        } else {
            containerColor.copy(alpha = 0.24f)
        }
    val fallbackGloss =
        if (isLightGlass) {
            Color.White.copy(alpha = 0.12f)
        } else {
            Color.White.copy(alpha = 0.06f)
        }

    return this
        .shadow(
            elevation = fallbackShadow,
            shape = shape,
            clip = false,
            ambientColor = Color.Black.copy(alpha = if (isLightGlass) 0.10f else 0.18f),
            spotColor = Color.Black.copy(alpha = if (isLightGlass) 0.10f else 0.18f),
        )
        .border(width = borderWidth.coerceAtLeast(0.6.dp), color = fallbackBorderColor, shape = shape)
        .background(color = fallbackSurfaceTint, shape = shape)
        .drawWithContent {
            drawContent()
            drawRect(fallbackGloss)
        }
}
