package app.eatezy.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import app.eatezy.data.fmt
import com.composables.icons.lucide.ChevronLeft
import com.composables.icons.lucide.ChevronRight
import com.composables.icons.lucide.Lucide

@Composable
fun Panel(modifier: Modifier = Modifier, padding: Dp = S.s18, content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(S.radius))
            .background(C.surface)
            .border(1.dp, C.line, RoundedCornerShape(S.radius))
            .padding(padding),
        content = content,
    )
}

@Composable
fun SectionTitle(text: String, modifier: Modifier = Modifier) {
    Text(text.uppercase(), style = T.smallBold.copy(color = C.text3), modifier = modifier.padding(start = S.s6, bottom = S.s6, top = S.s18))
}

/** A row inside a [Panel] list: icon, title, value, chevron. */
@Composable
fun ListRow(
    title: String,
    icon: ImageVector? = null,
    value: String? = null,
    subtitle: String? = null,
    tint: Color = C.text2,
    chevron: Boolean = true,
    onClick: (() -> Unit)? = null,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .heightIn(min = S.s36 + S.s6)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .padding(vertical = S.s6 + 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = tint, modifier = Modifier.size(S.s18 + 2.dp))
            Spacer(Modifier.width(S.s12))
        }
        Column(Modifier.weight(1f)) {
            Text(title, style = T.body)
            if (subtitle != null) Text(subtitle, style = T.small, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (value != null) Text(value, style = T.body.copy(color = C.text2), textAlign = TextAlign.End)
        if (chevron && onClick != null) {
            Spacer(Modifier.width(S.s6))
            Icon(Lucide.ChevronRight, null, tint = C.text3, modifier = Modifier.size(S.s18))
        }
    }
}

@Composable
fun Divider() = HorizontalDivider(color = C.line, thickness = 1.dp)

@Composable
fun Chip(text: String, selected: Boolean, onClick: () -> Unit, icon: ImageVector? = null) {
    Row(
        Modifier
            .clip(CircleShape)
            .background(if (selected) C.text else C.surface2)
            .clickable(onClick = onClick)
            .padding(horizontal = S.s12, vertical = S.s6),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (selected) C.bg else C.text2, modifier = Modifier.size(S.s18))
            Spacer(Modifier.width(S.s6))
        }
        Text(text, style = T.smallBold.copy(color = if (selected) C.bg else C.text2))
    }
}

@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true, color: Color = C.accent, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = S.s48)
            .clip(RoundedCornerShape(S.radius))
            .background(if (enabled) color else C.surface2)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = S.s24, vertical = S.s12),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = if (enabled) C.text else C.text3, modifier = Modifier.size(S.s18))
            Spacer(Modifier.width(S.s6))
        }
        Text(text, style = T.bodyBold.copy(color = if (enabled) C.text else C.text3))
    }
}

@Composable
fun GhostButton(text: String, modifier: Modifier = Modifier, icon: ImageVector? = null, color: Color = C.text, onClick: () -> Unit) {
    Row(
        modifier
            .heightIn(min = S.s48)
            .clip(RoundedCornerShape(S.radius))
            .border(1.dp, C.line, RoundedCornerShape(S.radius))
            .clickable(onClick = onClick)
            .padding(horizontal = S.s18, vertical = S.s12),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = color, modifier = Modifier.size(S.s18))
            Spacer(Modifier.width(S.s6))
        }
        Text(text, style = T.bodyBold.copy(color = color))
    }
}

@Composable
fun RoundIcon(icon: ImageVector, description: String, modifier: Modifier = Modifier, size: Dp = S.s48, bg: Color = C.surface2, tint: Color = C.text, onClick: () -> Unit) {
    Box(
        modifier.size(size).clip(CircleShape).background(bg).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(icon, description, tint = tint, modifier = Modifier.size(minOf(size / 2, S.s24) - 2.dp)) }
}

/** Plain text input in a rounded box. */
@Composable
fun Field(
    value: String,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    placeholder: String = "",
    number: Boolean = false,
    singleLine: Boolean = true,
    style: TextStyle = T.body,
    minLines: Int = 1,
) {
    Box(
        modifier
            .clip(RoundedCornerShape(S.radiusSmall))
            .background(C.surface2)
            .padding(horizontal = S.s12, vertical = S.s12),
    ) {
        if (value.isEmpty()) Text(placeholder, style = style.copy(color = C.text3))
        BasicTextField(
            value, onChange,
            textStyle = style,
            singleLine = singleLine,
            minLines = minLines,
            cursorBrush = SolidColor(C.accent),
            keyboardOptions = if (number) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

/** Number input that keeps what the user types and reports parsed values. */
@Composable
fun NumberField(value: Double, onValue: (Double) -> Unit, modifier: Modifier = Modifier, digits: Int = 1, style: TextStyle = T.body, suffix: String? = null) {
    var text by remember { mutableStateOf(value.fmt(digits)) }
    LaunchedEffect(value) {
        if (text.replace(',', '.').toDoubleOrNull() != value) text = value.fmt(digits)
    }
    Row(
        modifier.clip(RoundedCornerShape(S.radiusSmall)).background(C.surface2).padding(horizontal = S.s12, vertical = S.s12),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BasicTextField(
            text,
            { s ->
                text = s
                s.replace(',', '.').toDoubleOrNull()?.let(onValue)
            },
            textStyle = style.copy(textAlign = TextAlign.End),
            singleLine = true,
            cursorBrush = SolidColor(C.accent),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            modifier = Modifier.weight(1f),
        )
        if (suffix != null) {
            Spacer(Modifier.width(S.s6))
            Text(suffix, style = style.copy(color = C.text2))
        }
    }
}

/** Thin progress bar; turns accent when a limit is passed. */
@Composable
fun Bar(fraction: Float, color: Color, modifier: Modifier = Modifier, height: Dp = 4.dp) {
    Box(modifier.fillMaxWidth().height(height).clip(CircleShape).background(C.surface2)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).height(height).clip(CircleShape).background(color))
    }
}

@Composable
fun MacroPills(p: Double, c: Double, f: Double, modifier: Modifier = Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(S.s6)) {
        Pill(t("P", "Б") + " " + p.fmt(1), C.protein)
        Pill(t("C", "У") + " " + c.fmt(1), C.carbs)
        Pill(t("F", "Ж") + " " + f.fmt(1), C.fat)
    }
}

@Composable
fun Pill(text: String, @Suppress("UNUSED_PARAMETER") color: Color = C.text2) {
    Text(
        text,
        style = T.small.copy(color = C.text2),
        modifier = Modifier.clip(CircleShape).background(C.surface2).padding(horizontal = S.s6 + 2.dp, vertical = 1.dp),
    )
}

@Composable
fun Header(title: String, onBack: (() -> Unit)?, vertical: Dp = S.s12, actions: @Composable RowScope.() -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(horizontal = S.s12, vertical = vertical),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) RoundIcon(Lucide.ChevronLeft, t("Back", "Назад"), size = S.s48, bg = Color.Transparent, onClick = onBack)
        else Spacer(Modifier.width(S.s12))
        Text(title, style = T.h2, modifier = Modifier.weight(1f).padding(horizontal = S.s6), maxLines = 1, overflow = TextOverflow.Ellipsis)
        actions()
    }
}
