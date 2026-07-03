package `fun`.walawe.memechat.ui.components

import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.TextUnit
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.BasicRichText
import com.halilibo.richtext.ui.RichTextThemeProvider

@Composable
fun MarkdownContent(
    content: String,
    color: Color,
    modifier: Modifier = Modifier,
    fontSize: TextUnit = MaterialTheme.typography.bodyMedium.fontSize,
) {
    RichTextThemeProvider(
        textStyleProvider = { TextStyle(color = color, fontSize = fontSize) },
        contentColorProvider = { color },
        textStyleBackProvider = { newStyle, children ->
            CompositionLocalProvider(LocalTextStyle provides newStyle) { children() }
        },
        contentColorBackProvider = { newColor, children ->
            CompositionLocalProvider(LocalContentColor provides newColor) { children() }
        },
    ) {
        BasicRichText(modifier = modifier) {
            Markdown(content = content)
        }
    }
}
