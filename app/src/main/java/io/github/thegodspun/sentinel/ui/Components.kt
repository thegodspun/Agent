package io.github.thegodspun.sentinel.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import java.time.LocalTime

/** Space the floating tab bar takes at the bottom, so scrolling content can end above it. */
val LocalBottomBarSpace = compositionLocalOf { 0.dp }

/** Height of the floating tab bar, not counting the system navigation bar. */
val TAB_BAR_HEIGHT = 84.dp

/** A highlight across the top half, like light on glass. */
fun Modifier.gloss(strength: Float): Modifier = drawBehind {
    drawRect(
        Brush.verticalGradient(listOf(SentinelColors.White.copy(alpha = strength), Color.Transparent), endY = size.height * 0.55f),
        size = Size(size.width, size.height * 0.55f),
    )
}

/** A bright top edge fading out toward the bottom, for glass surfaces. */
fun glassEdge(dark: Boolean): Brush = Brush.verticalGradient(
    if (dark) listOf(SentinelColors.White.copy(alpha = 0.16f), SentinelColors.White.copy(alpha = 0.03f))
    else listOf(SentinelColors.White, SentinelColors.BlueLight.copy(alpha = 0.35f))
)

/** The app background: soft blue and green light pooling in the corners, behind the glass. */
@Composable
fun SentinelBackground(content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit) {
    val dark = isSystemInDarkTheme()
    val base = MaterialTheme.colorScheme.background
    Box(
        Modifier
            .fillMaxSize()
            .drawBehind {
                drawRect(base)
                val w = size.width
                val h = size.height
                drawCircle(
                    Brush.radialGradient(
                        listOf(SentinelColors.BlueBright.copy(alpha = if (dark) 0.22f else 0.14f), Color.Transparent),
                        Offset(w, h * 0.35f), w * 0.9f,
                    ),
                    radius = w * 0.9f, center = Offset(w, h * 0.35f),
                )
                drawCircle(
                    Brush.radialGradient(
                        listOf(SentinelColors.GreenBright.copy(alpha = if (dark) 0.14f else 0.10f), Color.Transparent),
                        Offset(0f, h * 0.85f), w * 0.85f,
                    ),
                    radius = w * 0.85f, center = Offset(0f, h * 0.85f),
                )
            },
        content = content,
    )
}

fun greeting(now: LocalTime = LocalTime.now()): String = when (now.hour) {
    in 4..11 -> "Good morning"
    in 12..16 -> "Good afternoon"
    in 17..21 -> "Good evening"
    else -> "Hello"
}

/**
 * The gradient header every screen opens with. It draws behind the status bar and rounds
 * off at the bottom, so the content below reads as a sheet.
 */
@Composable
fun GradientHeader(
    title: String,
    subtitle: String? = null,
    eyebrow: String? = null,
    action: (@Composable () -> Unit)? = null,
    content: (@Composable ColumnScope.() -> Unit)? = null,
) {
    val base = SentinelBrushes.Header
    // Two soft lights, green and blue, drift slowly across the header like an aurora.
    val drift by rememberInfiniteTransition(label = "aurora").animateFloat(
        initialValue = 0f, targetValue = 1f,
        animationSpec = infiniteRepeatable(tween(16_000, easing = LinearEasing), RepeatMode.Reverse),
        label = "drift",
    )
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(16.dp, RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp), ambientColor = SentinelColors.Blue, spotColor = SentinelColors.Blue)
            .clip(RoundedCornerShape(bottomStart = 32.dp, bottomEnd = 32.dp))
            .drawBehind {
                drawRect(base)
                val w = size.width
                val h = size.height
                val green = Offset(w * (0.1f + 0.35f * drift), h * 0.95f)
                drawCircle(
                    Brush.radialGradient(listOf(SentinelColors.GreenBright.copy(alpha = 0.32f), Color.Transparent), green, w * 0.55f),
                    radius = w * 0.55f, center = green,
                )
                val blue = Offset(w * (0.95f - 0.3f * drift), h * 0.05f)
                drawCircle(
                    Brush.radialGradient(listOf(SentinelColors.BlueLight.copy(alpha = 0.38f), Color.Transparent), blue, w * 0.6f),
                    radius = w * 0.6f, center = blue,
                )
                // Glossy top half, like polished glass.
                drawRect(
                    Brush.verticalGradient(listOf(SentinelColors.White.copy(alpha = 0.16f), Color.Transparent), endY = h * 0.55f),
                    size = Size(w, h * 0.55f),
                )
            }
            .windowInsetsPadding(WindowInsets.statusBars)
            .padding(start = 22.dp, end = 14.dp, top = 14.dp, bottom = 24.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                if (eyebrow != null) {
                    Text(eyebrow.uppercase(), style = MaterialTheme.typography.labelMedium, color = SentinelColors.GreenLight)
                    Spacer(Modifier.height(4.dp))
                }
                Text(title, style = MaterialTheme.typography.headlineMedium, color = SentinelColors.White)
                if (subtitle != null) {
                    Spacer(Modifier.height(2.dp))
                    Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = SentinelColors.White.copy(alpha = 0.78f))
                }
            }
            action?.invoke()
        }
        if (content != null) {
            Spacer(Modifier.height(18.dp))
            Column(Modifier.padding(end = 8.dp), content = content)
        }
    }
}

/** A frosted panel for use on top of the header gradient. */
@Composable
fun GlassPanel(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(SentinelColors.White.copy(alpha = 0.13f))
            .gloss(0.14f)
            .border(
                1.dp,
                Brush.verticalGradient(listOf(SentinelColors.White.copy(alpha = 0.45f), SentinelColors.White.copy(alpha = 0.08f))),
                RoundedCornerShape(22.dp),
            )
            .padding(16.dp),
        content = content,
    )
}

/** A round icon button that sits on the header gradient. */
@Composable
fun HeaderIconButton(icon: ImageVector, description: String, onClick: () -> Unit) {
    Box(
        Modifier
            .size(48.dp)
            .clip(CircleShape)
            .background(SentinelColors.White.copy(alpha = 0.14f))
            .clickable(role = Role.Button, onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = SentinelColors.White, modifier = Modifier.size(22.dp)) }
}

/** The basic content card: rounded, softly lifted, no outline. */
@Composable
fun SentinelCard(
    modifier: Modifier = Modifier,
    padding: Dp = 18.dp,
    onClick: (() -> Unit)? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val shape = RoundedCornerShape(24.dp)
    val dark = isSystemInDarkTheme()
    Column(
        modifier
            .fillMaxWidth()
            .shadow(8.dp, shape, ambientColor = SentinelColors.Blue.copy(alpha = 0.12f), spotColor = SentinelColors.Blue.copy(alpha = 0.18f))
            .clip(shape)
            // Frosted glass: mostly opaque, so the background glow shows through a little.
            .background(MaterialTheme.colorScheme.surfaceContainer.copy(alpha = if (dark) 0.78f else 0.86f))
            .gloss(if (dark) 0.05f else 0.5f)
            .border(1.dp, glassEdge(dark), shape)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(start = 6.dp, end = 6.dp, top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(text, style = MaterialTheme.typography.titleLarge, modifier = Modifier.weight(1f))
        trailing?.invoke()
    }
}

/** A filled circle holding an icon, used as a leading badge in rows and cards. */
@Composable
fun IconBadge(icon: ImageVector, tint: Color = SentinelColors.White, brush: Brush = SentinelBrushes.Outgoing, size: Dp = 40.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(brush), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = tint, modifier = Modifier.size(size * 0.5f))
    }
}

/** A small rounded label, e.g. a category or due date. */
@Composable
fun Pill(text: String, container: Color, content: Color, modifier: Modifier = Modifier) {
    Text(
        text,
        modifier = modifier.clip(CircleShape).background(container).padding(horizontal = 10.dp, vertical = 4.dp),
        style = MaterialTheme.typography.labelMedium,
        color = content,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
    )
}

/** A big pill-shaped button with a gradient, for the one main action on a screen. */
@Composable
fun GradientButton(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    icon: ImageVector? = null,
    brush: Brush = SentinelBrushes.Go,
) {
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = 56.dp)
            .shadow(10.dp, CircleShape, ambientColor = SentinelColors.Green.copy(alpha = 0.3f), spotColor = SentinelColors.Green.copy(alpha = 0.35f))
            .clip(CircleShape)
            .background(brush)
            .gloss(0.32f)
            .border(1.dp, Brush.verticalGradient(listOf(SentinelColors.White.copy(alpha = 0.5f), Color.Transparent)), CircleShape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 22.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = SentinelColors.White, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
        }
        Text(text, style = MaterialTheme.typography.titleMedium, color = SentinelColors.White)
    }
}

/** A quieter companion to [GradientButton]. */
@Composable
fun SoftButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier
            .heightIn(min = 48.dp)
            .clip(CircleShape)
            .background(MaterialTheme.colorScheme.primaryContainer)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = MaterialTheme.colorScheme.onPrimaryContainer, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onPrimaryContainer)
    }
}

/** A setting with a title, a one-line explanation and a switch; the whole row is tappable. */
@Composable
fun SettingSwitch(title: String, detail: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = 64.dp)
            .clip(RoundedCornerShape(16.dp))
            .clickable(role = Role.Switch) { onChange(!checked) }
            .padding(vertical = 10.dp, horizontal = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(end = 12.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            if (detail != null) Text(detail, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(
            checked = checked,
            onCheckedChange = onChange,
            colors = SwitchDefaults.colors(
                checkedThumbColor = SentinelColors.White,
                checkedTrackColor = MaterialTheme.colorScheme.secondary,
                checkedBorderColor = Color.Transparent,
            ),
        )
    }
}

@Composable
fun RowDivider() = HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant, thickness = 1.dp)

/** A borderless rounded text field that matches the cards. */
@Composable
fun SoftTextField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    placeholder: String? = null,
    singleLine: Boolean = true,
    minLines: Int = 1,
    visualTransformation: VisualTransformation = VisualTransformation.None,
    keyboardOptions: androidx.compose.foundation.text.KeyboardOptions = androidx.compose.foundation.text.KeyboardOptions.Default,
    supporting: String? = null,
) {
    Column(modifier.fillMaxWidth()) {
        TextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            placeholder = if (placeholder != null) {
                { Text(placeholder) }
            } else null,
            singleLine = singleLine,
            minLines = minLines,
            visualTransformation = visualTransformation,
            keyboardOptions = keyboardOptions,
            shape = RoundedCornerShape(16.dp),
            colors = TextFieldDefaults.colors(
                focusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                unfocusedContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                focusedIndicatorColor = Color.Transparent,
                unfocusedIndicatorColor = Color.Transparent,
                disabledIndicatorColor = Color.Transparent,
            ),
            modifier = Modifier.fillMaxWidth(),
        )
        if (supporting != null) {
            Text(
                supporting,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp, top = 6.dp),
            )
        }
    }
}

data class NavTab(val label: String, val icon: ImageVector)

/** A floating, pill-shaped tab bar. The selected tab grows to show its name. */
@Composable
fun PillNavBar(tabs: List<NavTab>, selected: Int, onSelect: (Int) -> Unit) {
    Box(
        Modifier
            .fillMaxWidth()
            .windowInsetsPadding(WindowInsets.navigationBars)
            .padding(horizontal = 18.dp, vertical = 10.dp),
    ) {
        val dark = isSystemInDarkTheme()
        Row(
            Modifier
                .fillMaxWidth()
                .shadow(20.dp, CircleShape, ambientColor = SentinelColors.Navy.copy(alpha = 0.3f), spotColor = SentinelColors.Navy.copy(alpha = 0.35f))
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = if (dark) 0.88f else 0.9f))
                .gloss(if (dark) 0.06f else 0.6f)
                .border(1.dp, glassEdge(dark), CircleShape)
                .padding(6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            tabs.forEachIndexed { index, tab -> NavItem(tab, index == selected) { onSelect(index) } }
        }
    }
}

@Composable
private fun RowScope.NavItem(tab: NavTab, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .weight(if (selected) 1.6f else 1f)
            .height(52.dp)
            .clip(CircleShape)
            .then(if (selected) Modifier.background(SentinelBrushes.Outgoing).gloss(0.3f) else Modifier)
            .clickable(role = Role.Tab, onClickLabel = tab.label, onClick = onClick)
            .animateContentSize(tween(220, easing = FastOutSlowInEasing)),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            tab.icon,
            contentDescription = if (selected) null else tab.label,
            tint = if (selected) SentinelColors.White else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
        if (selected) {
            Spacer(Modifier.width(8.dp))
            Text(tab.label, style = MaterialTheme.typography.labelLarge, color = SentinelColors.White, fontWeight = FontWeight.SemiBold, maxLines = 1)
        }
    }
}

/** The green voice button. A soft ring pulses around it while Sentinel is working. */
@Composable
fun MicButton(busy: Boolean, onClick: () -> Unit) {
    val pulse = rememberInfiniteTransition(label = "pulse")
    val scale by pulse.animateFloat(
        initialValue = 1f, targetValue = 1.35f,
        animationSpec = infiniteRepeatable(tween(1100, easing = FastOutSlowInEasing), RepeatMode.Restart),
        label = "scale",
    )
    val alpha by pulse.animateFloat(
        initialValue = 0.45f, targetValue = 0f,
        animationSpec = infiniteRepeatable(tween(1100, easing = LinearEasing), RepeatMode.Restart),
        label = "alpha",
    )
    Box(Modifier.size(60.dp), contentAlignment = Alignment.Center) {
        if (busy) {
            Box(
                Modifier
                    .size(56.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .clip(CircleShape)
                    .background(SentinelColors.GreenBright),
            )
        }
        Box(
            Modifier
                .size(56.dp)
                .shadow(12.dp, CircleShape, ambientColor = SentinelColors.Green.copy(alpha = 0.4f), spotColor = SentinelColors.Green.copy(alpha = 0.5f))
                .clip(CircleShape)
                .background(SentinelBrushes.Go)
                .gloss(0.38f)
                .border(1.dp, Brush.verticalGradient(listOf(SentinelColors.White.copy(alpha = 0.55f), Color.Transparent)), CircleShape)
                .clickable(role = Role.Button, onClickLabel = "Talk to Sentinel", onClick = onClick),
            contentAlignment = Alignment.Center,
        ) { Icon(SentinelIcons.Mic, "Talk", tint = SentinelColors.White, modifier = Modifier.size(26.dp)) }
    }
}

/** Round blue send button that replaces the mic while there's text to send. */
@Composable
fun SendButton(onClick: () -> Unit) {
    Box(
        Modifier
            .size(56.dp)
            .shadow(10.dp, CircleShape, ambientColor = SentinelColors.Blue.copy(alpha = 0.35f), spotColor = SentinelColors.Blue.copy(alpha = 0.4f))
            .clip(CircleShape)
            .background(SentinelBrushes.Outgoing)
            .gloss(0.35f)
            .border(1.dp, Brush.verticalGradient(listOf(SentinelColors.White.copy(alpha = 0.5f), Color.Transparent)), CircleShape)
            .clickable(role = Role.Button, onClickLabel = "Send", onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(SentinelIcons.Send, "Send", tint = SentinelColors.White, modifier = Modifier.size(22.dp)) }
}

/** Three dots that rise in turn, shown while Sentinel is thinking. */
@Composable
fun TypingDots(color: Color = MaterialTheme.colorScheme.primary) {
    val transition = rememberInfiniteTransition(label = "dots")
    Row(horizontalArrangement = Arrangement.spacedBy(5.dp), verticalAlignment = Alignment.CenterVertically) {
        repeat(3) { i ->
            val lift by transition.animateFloat(
                initialValue = 0f, targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    tween(600, delayMillis = i * 150, easing = FastOutSlowInEasing),
                    RepeatMode.Reverse,
                ),
                label = "dot$i",
            )
            Box(
                Modifier
                    .size(8.dp)
                    .graphicsLayer { translationY = -6f * lift; alpha = 0.4f + 0.6f * lift }
                    .clip(CircleShape)
                    .background(color),
            )
        }
    }
}

/** Centered message for an empty list. */
@Composable
fun EmptyState(icon: ImageVector, title: String, body: String) {
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 28.dp, vertical = 36.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        IconBadge(icon, size = 64.dp)
        Spacer(Modifier.height(16.dp))
        Text(title, style = MaterialTheme.typography.titleLarge)
        Spacer(Modifier.height(6.dp))
        Text(
            body,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        )
    }
}
