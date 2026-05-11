package `fun`.walawe.memechat.ui.screen

import android.content.res.Configuration
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.outlined.AddComment
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import `fun`.walawe.memechat.R
import kotlinx.coroutines.launch

// Dummy models
private data class DummyMessage(
    val id: String,
    val role: MessageRole,
    val text: String,
    val time: String,
    val imageUri: String? = null
)

private enum class MessageRole {
    User,
    Assistant
}

private data class DummyConversation(
    val id: String,
    val title: String,
    val preview: String,
    val time: String
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen() {
    var messages by remember {
        mutableStateOf(
            listOf(
                DummyMessage(
                    id = "m5",
                    role = MessageRole.User,
                    text = "Show me the meme in a funny way.",
                    time = "10:32",
                    imageUri = "https://dummyimage.com/300x200/efefef/111111.png&text=Meme"
                ),
                DummyMessage(
                    id = "m4",
                    role = MessageRole.Assistant,
                    text = "This meme is basically your CPU trying to keep up with tabs...",
                    time = "10:33"
                ),
                DummyMessage(
                    id = "m3",
                    role = MessageRole.User,
                    text = "Describe this image.",
                    time = "10:31",
                    imageUri = "https://dummyimage.com/300x200/eeeeee/333333.png&text=Image"
                ),
                DummyMessage(
                    id = "m2",
                    role = MessageRole.Assistant,
                    text = "Looks like a cat judging your life choices.",
                    time = "10:31"
                ),
                DummyMessage(
                    id = "m1",
                    role = MessageRole.User,
                    text = "Hello Qwen!",
                    time = "10:30"
                )
            )
        )
    }
    var inputText by remember { mutableStateOf("") }
    var isProcessing by remember { mutableStateOf(false) }
    var showImagePickerHint by remember { mutableStateOf(false) }

    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val scope = rememberCoroutineScope()

    val conversations = remember {
        listOf(
            DummyConversation("c1", "Qwen Chat", "Latest: cat meme", "10:33"),
            DummyConversation("c2", "Work notes", "Summarize standup", "09:10"),
            DummyConversation("c3", "Trip planning", "Best places in Seoul", "Yesterday"),
            DummyConversation("c4", "Ideas", "Brand voice notes", "Yesterday"),
            DummyConversation("c5", "Ideas", "Brand voice notes", "Yesterday"),
            DummyConversation("c6", "Ideas", "Brand voice notes", "Yesterday"),
            DummyConversation("c7", "Ideas", "Brand voice notes", "Yesterday"),
        )
    }
    var selectedConversationId by remember { mutableStateOf("c1") }

    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            DrawerContent(
                conversations = conversations,
                selectedConversationId = selectedConversationId,
                onNewChat = {
                    selectedConversationId = "c1"
                    // Backend integration point: create new conversation
                },
                onSelectConversation = { id ->
                    selectedConversationId = id
                    // Backend integration point: load conversation
                }
            )
        }
    ) {
        Scaffold(
            contentWindowInsets = WindowInsets.statusBars
                .add(WindowInsets.navigationBars)
                .add(WindowInsets.displayCutout),
            topBar = {
                CenterAlignedTopAppBar(
                    modifier = Modifier.fillMaxWidth().shadow(4.dp),
                    title = {
                        Text(
                            text = "New Chat",
                            style = MaterialTheme.typography.titleMedium
                        )
                    },
                    windowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
                    navigationIcon = {
                        IconButton(onClick = { scope.launch { drawerState.open() } }) {
                            Icon(
                                imageVector = Icons.Filled.Menu,
                                contentDescription = "Open drawer"
                            )
                        }
                    },
                    actions = {
                        IconButton(onClick = { }) {
                            Icon(
                                imageVector = Icons.Outlined.AddComment,
                                contentDescription = "New chat"
                            )
                        }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer,
                        navigationIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        titleContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                        actionIconContentColor = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                )
            },
            bottomBar = {
                InputBar(
                    message = inputText,
                    isProcessing = isProcessing,
                    onInputChange = {inputText = it},
                    onAttach = { },
                    onSend = {
                        if (inputText.isNotBlank()) {
                            inputText = ""
                            isProcessing = true
                        }
                    }
                )
            }
        ) { padding ->
            Box(modifier = Modifier
                .fillMaxSize()
                .padding(padding)) {
                MessageList(messages = messages)
                if (isProcessing) {
                    LoadingOverlay()
                }
            }
        }
    }
}

@Composable
private fun DrawerContent(
    conversations: List<DummyConversation>,
    selectedConversationId: String,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit
) {
    ModalDrawerSheet {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {
            Column(
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .fillMaxWidth()
                    .padding(bottom = 70.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(text = "Conversations", style = MaterialTheme.typography.titleMedium)
                    OutlinedButton(
                        onClick = onNewChat,
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Filled.Add,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(text = "New Chat")
                    }
                }

                HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

                LazyColumn(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    items(conversations) { convo ->
                        val selected = convo.id == selectedConversationId
                        val containerColor = if (selected) {
                            MaterialTheme.colorScheme.secondaryContainer
                        } else {
                            MaterialTheme.colorScheme.surface
                        }
                        Column(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(containerColor)
                                .clickable { onSelectConversation(convo.id) }
                                .padding(12.dp)
                                .fillMaxWidth()
                        ) {
                            Text(
                                text = convo.title,
                                style = MaterialTheme.typography.bodyMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            Text(
                                text = convo.preview,
                                style = MaterialTheme.typography.bodyMedium,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            Text(
                                text = convo.time,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            Column(
                modifier = Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .padding(start = 12.dp, end = 12.dp, bottom = 6.dp)
            ) {
                HorizontalDivider()
                SettingsDrawerItem(onClick = {  })
            }
        }
    }
}

@Composable
private fun MessageList(messages: List<DummyMessage>) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        reverseLayout = true,
        state = listState,
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = PaddingValues(vertical = 16.dp)
    ) {
        items(messages) { message ->
            MessageBubble(message = message)
        }
    }
}

@Composable
private fun MessageBubble(message: DummyMessage) {
    val isUser = message.role == MessageRole.User
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 4.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    }
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        Card(
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            elevation = if (isUser) CardDefaults.cardElevation(0.dp) else CardDefaults.cardElevation(
                1.dp
            ),
            modifier = Modifier.fillMaxWidth(0.9f)
        ) {
            Column(
                modifier = Modifier.padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                message.imageUri?.let {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(it)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Attachment",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(160.dp)
                            .clip(RoundedCornerShape(12.dp))
                            .background(MaterialTheme.colorScheme.surfaceVariant),
                        placeholder = painterResource(id = R.drawable.placeholder)
                    )
                }
                Text(text = message.text, style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message.time,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
fun InputBar(
    message: String,
    isProcessing: Boolean,
    onInputChange: (String) -> Unit,
    onAttach: () -> Unit,
    onSend: () -> Unit
) {

    // Input area at bottom
    Surface(
        color = Color.White,
        tonalElevation = 2.dp
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp)
        ) {
            Surface(
                color = MaterialTheme.colorScheme.surfaceBright,
                shape = RoundedCornerShape(26.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                BasicTextField(
                    value = message,
                    onValueChange = onInputChange,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 20.dp, vertical = 12.dp),
                    textStyle = LocalTextStyle.current.copy(
                        fontSize = 14.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    ),
                    cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
                    singleLine = false,
                    maxLines = 4,
                    decorationBox = { innerTextField ->
                        if (message.isEmpty()) {
                            Text(
                                text = "Explain this meme",
                                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                                fontSize = 16.sp
                            )
                        }
                        innerTextField()
                    }
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    ModelButton(
                        onClick = { },
                        icon = Icons.Filled.AutoAwesome,
                        text = "QWEN VL",
                        isHighlight = true
                    )

                    ModelButton(
                        onClick = { },
                        icon = Icons.Filled.Image,
                        text = "Image",
                        isHighlight = false
                    )
                }

                FloatingActionButton(
                    onClick = { },
                    containerColor = if (message.isNotEmpty()) {
                        MaterialTheme.colorScheme.primaryFixed
                    } else MaterialTheme.colorScheme.primaryFixed.copy(
                        alpha = 0.8f
                    ),
                    modifier = Modifier.size(40.dp),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowUpward,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        }
    }
}

@Composable
private fun LoadingOverlay() {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.scrim.copy(alpha = 0.3f)),
        contentAlignment = Alignment.Center
    ) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.lottieflow_loading))
        LottieAnimation(
            composition = composition,
            modifier = Modifier.size(120.dp),
            iterations = Int.MAX_VALUE
        )
    }
}


@Composable
fun ModelButton(
    onClick: () -> Unit,
    icon: ImageVector,
    text: String,
    isHighlight: Boolean
) {
    Surface(
        onClick = onClick,
        color = if (isHighlight) {
            MaterialTheme.colorScheme.surfaceVariant
        } else Color.Transparent,
        shape = RoundedCornerShape(8.dp),
        border = if (isHighlight)
            BorderStroke(
                1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)
            ) else null
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = icon,
                contentDescription = text,
                tint = if (isHighlight) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                fontSize = 13.sp,
                color = if (isHighlight) {
                    MaterialTheme.colorScheme.onSurfaceVariant
                } else MaterialTheme.colorScheme.onSurface,
                fontWeight = if (isHighlight) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

@Composable
fun SettingsDrawerItem(onClick: () -> Unit) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        color = Color.Transparent,
        onClick = onClick
    ) {
        Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Icon(
                imageVector = Icons.Default.Settings,
                contentDescription = "Settings",
                tint = Color.Gray,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Settings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = Color.Black
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = Color.Gray.copy(alpha = 0.6f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}
@Preview(showBackground = true)
@Composable
private fun ChatScreenPreviewLight() {
    MaterialTheme {
        Surface {
            ChatScreen()
        }
    }
}

@Preview(
    showBackground = true,
    uiMode = Configuration.UI_MODE_NIGHT_YES
)
@Composable
private fun ChatScreenPreviewDark() {
    MaterialTheme(colorScheme = MaterialTheme.colorScheme.copy()) {
        Surface(color = MaterialTheme.colorScheme.background) {
            ChatScreen()
        }
    }
}
