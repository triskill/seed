package com.seed.app.ui.shell

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Renders one [OutputLine] as a single row in the
 * shell's output `LazyColumn`.
 *
 * The four [OutputLine] subclasses map to four
 * visual variants:
 *
 *   - [OutputLine.Command] → `$ <text>` with the
 *                             prompt prefix in
 *                             primary colour and
 *                             the command in
 *                             onSurface.
 *   - [OutputLine.Stdout]  → plain text, onSurface.
 *   - [OutputLine.Stderr]  → plain text, error
 *                             colour.
 *   - [OutputLine.Exit]    → `[exit N]` in a
 *                             muted style (a
 *                             slightly faded
 *                             onSurfaceVariant).
 *
 * Every variant is monospaced (so columns line up
 * the way they would in a real terminal) and
 * `bodySmall` (so the rows are dense — a real
 * shell log scrolls fast and 16 sp body text is
 * too tall).
 *
 * The four variants are `private` Composables
 * because they're not interesting on their own —
 * the entry point is the sealed-class `when` in
 * [OutputLineRow] which makes the exhaustiveness
 * check verify all four cases are handled.
 */
@Composable
fun OutputLineRow(
    line: OutputLine,
    modifier: Modifier = Modifier,
) {
    when (line) {
        is OutputLine.Command ->
            CommandLine(text = line.text, modifier = modifier)
        is OutputLine.Stdout ->
            PlainLine(text = line.text, modifier = modifier)
        is OutputLine.Stderr ->
            StderrLine(text = line.text, modifier = modifier)
        is OutputLine.Exit ->
            ExitLine(code = line.code, modifier = modifier)
    }
}

@Composable
private fun CommandLine(text: String, modifier: Modifier = Modifier) {
    // Two-tone: the `$` prompt in primary colour,
    // the command in onSurface. `AnnotatedString`
    // is cheaper than nesting two `Text`s in a
    // `Row` and keeps selection working as one
    // block.
    val styled: AnnotatedString = buildAnnotatedString {
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary)) {
            append("$ ")
        }
        withStyle(SpanStyle(color = LocalContentColor.current)) {
            append(text)
        }
    }
    Text(
        text = styled,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
private fun PlainLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSurface,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
private fun StderrLine(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text,
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.error,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}

@Composable
private fun ExitLine(code: Int, modifier: Modifier = Modifier) {
    Text(
        // Muted style — the exit code is metadata
        // ("how did it go?"), not the user's primary
        // information. `surfaceVariant` reads as
        // slightly faded text on the screen, which
        // signals "background" without going full
        // disabled-grey.
        text = "[exit $code]",
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        fontFamily = FontFamily.Monospace,
        fontSize = 13.sp,
    )
}
