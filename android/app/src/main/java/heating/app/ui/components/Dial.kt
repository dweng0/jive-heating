package heating.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LocalFireDepartment
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.ProgressBarRangeInfo
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.progressBarRangeInfo
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.setProgress
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import heating.app.ui.DIAL_START
import heating.app.ui.DIAL_SWEEP
import heating.app.ui.MAX_TARGET
import heating.app.ui.MIN_TARGET
import heating.app.ui.dialFraction
import heating.app.ui.formatTemp
import heating.app.ui.stepTarget
import heating.app.ui.targetForAngle
import heating.app.ui.theme.HeatingTheme
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * The Hive-style thermostat dial: a 270° arc from 5° to 30°, a knob at the target,
 * and the target in the middle. Drag or tap the arc to change the target.
 * [target] null means off. [onTargetChange] is called with half-degree values.
 */
@Composable
fun Dial(
    target: Double?,
    current: Double?,
    label: String,
    heating: Boolean,
    enabled: Boolean,
    onTargetChange: (Double) -> Unit,
    modifier: Modifier = Modifier,
    diameter: Dp = 300.dp,
) {
    val colors = HeatingTheme.colors
    val off = target == null
    val arcColor = if (off) MaterialTheme.colorScheme.outline else colors.heat
    val fraction = dialFraction(target)

    fun angleOf(pos: Offset, size: Float): Float {
        val c = size / 2f
        return Math.toDegrees(atan2((pos.y - c).toDouble(), (pos.x - c).toDouble())).toFloat()
    }

    Box(
        modifier = modifier
            .size(diameter)
            .semantics {
                contentDescription = if (off) "Heating off" else
                    "Target ${formatTemp(target)}, currently ${formatTemp(current)}"
                if (!off) {
                    progressBarRangeInfo = ProgressBarRangeInfo(
                        target!!.toFloat(), MIN_TARGET.toFloat()..MAX_TARGET.toFloat(), steps = 49,
                    )
                }
                if (enabled) setProgress { value -> onTargetChange(stepTarget(value.toDouble(), 0)); true }
            }
            .then(
                if (enabled) Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { onTargetChange(targetForAngle(angleOf(it, size.width.toFloat()))) }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures { change, _ ->
                            onTargetChange(targetForAngle(angleOf(change.position, size.width.toFloat())))
                        }
                    }
                else Modifier,
            ),
        contentAlignment = Alignment.Center,
    ) {
        val track = colors.track
        val knobFill = MaterialTheme.colorScheme.surface
        Canvas(Modifier.size(diameter)) {
            val stroke = 22.dp.toPx()
            val inset = stroke / 2 + 6.dp.toPx()
            val arcSize = Size(size.width - inset * 2, size.height - inset * 2)
            val topLeft = Offset(inset, inset)
            drawArc(track, DIAL_START, DIAL_SWEEP, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            if (!off) {
                drawArc(arcColor, DIAL_START, DIAL_SWEEP * fraction, false, topLeft, arcSize, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            val radius = arcSize.width / 2
            val angle = Math.toRadians((DIAL_START + DIAL_SWEEP * fraction).toDouble())
            val knob = Offset(center.x + radius * cos(angle).toFloat(), center.y + radius * sin(angle).toFloat())
            drawCircle(knobFill, 17.dp.toPx(), knob)
            drawCircle(arcColor, 17.dp.toPx(), knob, style = Stroke(5.dp.toPx()))
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(label.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                if (off) "Off" else formatTemp(target),
                style = MaterialTheme.typography.displayLarge,
                color = if (off) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onBackground,
            )
            Text("Now ${formatTemp(current)}", style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (heating && !off) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(Icons.Rounded.LocalFireDepartment, contentDescription = null, tint = colors.heatText, modifier = Modifier.size(16.dp))
                    Text("Heating", style = MaterialTheme.typography.labelMedium, color = colors.heatText)
                }
            }
        }
    }
}
