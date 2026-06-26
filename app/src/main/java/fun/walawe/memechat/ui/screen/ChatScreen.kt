package `fun`.walawe.memechat.ui.screen

import android.content.res.Configuration
import android.os.Build.VERSION.SDK_INT
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.add
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.material.icons.filled.Send
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.DrawerState
import androidx.compose.material3.DrawerValue
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalDrawerSheet
import androidx.compose.material3.ModalNavigationDrawer
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.VerticalDivider
import androidx.compose.material3.rememberDrawerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.ImageLoader
import coil.compose.AsyncImage
import coil.compose.rememberAsyncImagePainter
import coil.decode.GifDecoder
import coil.decode.ImageDecoderDecoder
import coil.request.ImageRequest
import com.airbnb.lottie.compose.LottieAnimation
import com.airbnb.lottie.compose.LottieCompositionSpec
import com.airbnb.lottie.compose.rememberLottieComposition
import com.halilibo.richtext.commonmark.Markdown
import com.halilibo.richtext.ui.BasicRichText
import com.halilibo.richtext.ui.RichTextThemeProvider
import `fun`.walawe.memechat.R
import `fun`.walawe.memechat.model.ChatMessage
import `fun`.walawe.memechat.model.ChatRole
import `fun`.walawe.memechat.model.ChatUiState
import `fun`.walawe.memechat.model.ConversationHistory
import `fun`.walawe.memechat.model.WebSearchMode
import `fun`.walawe.memechat.presenter.ChatViewModel
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ChatScreen(
    viewModel: ChatViewModel = hiltViewModel(),
    onOpenSettings: () -> Unit = {},
) {
    val scope = rememberCoroutineScope()
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val messages by viewModel.messages.collectAsStateWithLifecycle()
    val conversations by viewModel.conversations.collectAsStateWithLifecycle()
    val currentConversationId by viewModel.currentConversationId.collectAsStateWithLifecycle()

    var inputText by remember { mutableStateOf(TextFieldValue("")) }
    val drawerState = rememberDrawerState(DrawerValue.Closed)
    val keyboardController = LocalSoftwareKeyboardController.current

    val galleryLauncher =
        rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) {
            it?.let { uri ->
                viewModel.setSelectedImageUri(uri.toString())
            }
        }

    ChatScreenContent(
        uiState = uiState,
        messages = messages,
        inputText = inputText,
        onInputChange = { inputText = it },
        drawerState = drawerState,
        onOpenDrawer = { scope.launch { drawerState.open() } },
        onOpenSettings = onOpenSettings,
        conversations = conversations,
        selectedConversationId = currentConversationId ?: "",
        onNewChat = {
            scope.launch { drawerState.close() }
            viewModel.startNewConversation()
        },
        onSelectConversation = { id ->
            scope.launch { drawerState.close() }
            viewModel.loadConversation(id)
        },
        onDeleteConversation = { id ->
            viewModel.deleteConversation(id)
        },
        onAttach = {
            galleryLauncher.launch(
                PickVisualMediaRequest(
                    ActivityResultContracts.PickVisualMedia.ImageOnly
                )
            )
        },
        onRemoveImage = { viewModel.setSelectedImageUri(null) },
        onSend = {
            if (inputText.text.isNotBlank() && !uiState.isProcessing) {
                keyboardController?.hide()
                viewModel.sendMessage(inputText.text)
                inputText = TextFieldValue("")
                viewModel.setSelectedImageUri(null)
            }
        },
        onDismissError = viewModel::clearError,
        isThinkingEnabled = uiState.isThinkingEnabled,
        onToggleThinking = { viewModel.toggleThinking() },
        webSearchMode = uiState.webSearchMode,
        onToggleWebSearchMode = { viewModel.setWebSearchMode(
            when (uiState.webSearchMode) {
                WebSearchMode.None -> WebSearchMode.Search
                WebSearchMode.Search -> WebSearchMode.Fetch
                WebSearchMode.Fetch -> WebSearchMode.None
            }
        ) },
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreenContent(
    uiState: ChatUiState,
    messages: List<ChatMessage>,
    inputText: TextFieldValue,
    onInputChange: (TextFieldValue) -> Unit,
    drawerState: DrawerState,
    onOpenDrawer: () -> Unit,
    onOpenSettings: () -> Unit,
    conversations: List<ConversationHistory>,
    selectedConversationId: String,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onAttach: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    onDismissError: () -> Unit,
    isThinkingEnabled: Boolean,
    onToggleThinking: () -> Unit,
    webSearchMode: WebSearchMode = WebSearchMode.None,
    onToggleWebSearchMode: () -> Unit = {},
) {
    ModalNavigationDrawer(
        drawerState = drawerState,
        drawerContent = {
            ModalDrawerSheet(
                modifier = Modifier
                    .fillMaxHeight()
                    .fillMaxWidth(0.85f),
                drawerContainerColor = MaterialTheme.colorScheme.surface
            ) {
                DrawerContent(
                    conversations = conversations,
                    selectedConversationId = selectedConversationId,
                    onNewChat = onNewChat,
                    onSelectConversation = onSelectConversation,
                    onDeleteConversation = onDeleteConversation,
                    onOpenSettings = onOpenSettings
                )
            }
        },
        modifier = Modifier.fillMaxSize().imePadding(),
    ) {
        Box(modifier = Modifier.fillMaxSize().navigationBarsPadding()) {
            Scaffold(
                contentWindowInsets = WindowInsets.statusBars
                    .add(WindowInsets.displayCutout),
                topBar = {
                    CenterAlignedTopAppBar(
                        modifier = Modifier.fillMaxWidth().shadow(4.dp),
                        windowInsets = WindowInsets.statusBars.only(WindowInsetsSides.Top),
                        title = {
                            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                Text(
                                    text = if(uiState.isNewConversation) "New Conversation" else "MemeLM Chat",
                                    style = MaterialTheme.typography.titleMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    fontWeight = FontWeight.Medium
                                )
                                Text(
                                    text = "MiniCPM-V4.6",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        },
                        navigationIcon = {
                            IconButton(onClick = onOpenDrawer) {
                                Icon(painterResource(R.drawable.ic_menu),
                                    contentDescription = "Open drawer",
                                    modifier = Modifier.size(20.dp),
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        },
                        actions = {
                            IconButton(onClick = onNewChat) {
                                Icon(painterResource(R.drawable.ic_new_chat),
                                    contentDescription = "New Chat",
                                    modifier = Modifier.size(20.dp)
                                )
                            }
                        },
                        colors = TopAppBarDefaults.topAppBarColors(
                            containerColor = MaterialTheme.colorScheme.surfaceBright,
                            navigationIconContentColor = MaterialTheme.colorScheme.onSurface,
                            titleContentColor = MaterialTheme.colorScheme.onSurface,
                            actionIconContentColor = MaterialTheme.colorScheme.onSurface
                        )
                    )
                },
                bottomBar = {}
            ) { padding ->
                Box(modifier = Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                    MessageList(
                        messages = messages,
                        modifier = Modifier.padding(bottom = 120.dp),
                    )

                    val showIntroVideo = messages.isEmpty() &&
                            uiState.isNewConversation

                    if (showIntroVideo) {
                        LoopingWebmSnippet(
                            modifier = Modifier
                                .align(Alignment.Center)
                                .fillMaxWidth()
                                .padding(horizontal = 16.dp, vertical = 8.dp)
                        )
                    }

                    if (!uiState.error.isNullOrEmpty()) {
                        TransientErrorDialog(
                            message = uiState.error,
                            onDismiss = onDismissError,
                        )
                    }
                }
            }

            InputBar(
                inputText = inputText,
                selectedImageUri = uiState.selectedImageUri,
                onInputChange = onInputChange,
                onAttach = onAttach,
                onRemoveImage = onRemoveImage,
                onSend = onSend,
                isThinkingEnabled = isThinkingEnabled,
                onToggleThinking = onToggleThinking,
                webSearchMode = webSearchMode,
                onToggleWebSearchMode = onToggleWebSearchMode,
                modifier = Modifier.align(Alignment.BottomCenter),
            )
        }
    }
}

@Composable
private fun DrawerContent(
    conversations: List<ConversationHistory>,
    selectedConversationId: String,
    onNewChat: () -> Unit,
    onSelectConversation: (String) -> Unit,
    onDeleteConversation: (String) -> Unit,
    onOpenSettings: () -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
    ){
        Column(
            modifier =  Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .fillMaxHeight()
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
                    Icon(imageVector = Icons.Filled.Add, contentDescription = null, modifier = Modifier.size(18.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(text = "New Chat")
                }
            }

            HorizontalDivider(modifier = Modifier.padding(top = 4.dp))

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(conversations) { convo ->
                    ConversationRow(
                        conversation = convo,
                        isSelected = convo.id == selectedConversationId,
                        onClick = { onSelectConversation(convo.id) },
                        onDelete = { onDeleteConversation(convo.id) },
                    )
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
            SettingsDrawerItem(onClick = onOpenSettings)
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun ConversationRow(
    conversation: ConversationHistory,
    isSelected: Boolean,
    onClick: () -> Unit,
    onDelete: () -> Unit,
) {
    var showConfirm by remember { mutableStateOf(false) }
    val containerColor = if (isSelected) {
        MaterialTheme.colorScheme.secondaryContainer
    } else {
        MaterialTheme.colorScheme.surface
    }
    Column(
        modifier = Modifier
            .clip(RoundedCornerShape(12.dp))
            .background(containerColor)
            .border(BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant), RoundedCornerShape(12.dp))
            .combinedClickable(
                onClick = onClick,
                onLongClick = { showConfirm = true },
            )
            .fillMaxWidth()
            .padding(12.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = conversation.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = conversation.time,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }

    if (showConfirm) {
        AlertDialog(
            onDismissRequest = { showConfirm = false },
            title = { Text("Delete conversation?") },
            text = { Text("This will permanently remove the conversation and its images.") },
            confirmButton = {
                TextButton(onClick = {
                    showConfirm = false
                    onDelete()
                }) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { showConfirm = false }) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun MessageList(messages: List<ChatMessage>, modifier: Modifier = Modifier) {
    val listState = rememberLazyListState()
    LazyColumn(
        modifier = modifier
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
private fun MessageBubble(
    message: ChatMessage
) {
    val isUser = message.role == ChatRole.User
    val bubbleShape = if (isUser) {
        RoundedCornerShape(topStart = 18.dp, topEnd = 4.dp, bottomEnd = 18.dp, bottomStart = 18.dp)
    } else {
        RoundedCornerShape(topStart = 18.dp, topEnd = 18.dp, bottomEnd = 18.dp, bottomStart = 4.dp)
    }
    val bubbleColor = if (isUser) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.tertiaryContainer
    }
    val textColor = if (isUser) {
        MaterialTheme.colorScheme.onPrimaryContainer
    } else {
        MaterialTheme.colorScheme.onTertiaryContainer
    }
    val alignment = if (isUser) Alignment.End else Alignment.Start
    var collapseState by remember { mutableStateOf(true) }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = alignment
    ) {
        if(!isUser && message.reasoning.isNotEmpty()){
            CollapsibleReasoningSection(
                modifier = Modifier.padding(top =8.dp),
                title = Pair("Let me cook...", "Reasoning Process"),
                isStreaming = message.isStreaming,
                content = message.reasoning,
                isExpanded = collapseState,
                onToggle = { collapseState = !collapseState }
            )
        }

        val modifier = if (isUser) {
            Modifier.padding(top = 8.dp)
        } else {
            Modifier.fillMaxWidth(0.9f).padding(top = 12.dp)
        }
        Card(
            shape = bubbleShape,
            colors = CardDefaults.cardColors(containerColor = bubbleColor),
            elevation = if (isUser) CardDefaults.cardElevation(0.dp) else CardDefaults.cardElevation(1.dp),
            modifier = modifier
        ) {
            Column(
                modifier = Modifier.padding(12.dp)
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
                        placeholder = painterResource(id = R.drawable.placeholder),
                    )
                }

                if (isUser) {
                    Text(
                        text = message.text.trim().ifEmpty { "..." },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = MaterialTheme.typography.bodyMedium.fontWeight,
                        color = textColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                    )
                } else {
                    MarkdownContent(
                        content = message.text.trim().ifEmpty { "..." },
                        color = textColor,
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                    )
                }
            }
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = message.timestamp,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 8.dp)
        )
    }
}

@Composable
private fun InputBar(
    inputText: TextFieldValue,
    selectedImageUri: String?,
    onInputChange: (TextFieldValue) -> Unit,
    onAttach: () -> Unit,
    onRemoveImage: () -> Unit,
    onSend: () -> Unit,
    isThinkingEnabled: Boolean,
    onToggleThinking: () -> Unit,
    webSearchMode: WebSearchMode = WebSearchMode.None,
    onToggleWebSearchMode: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    var showAttachMenu by remember { mutableStateOf(false) }

    Card(
        modifier = modifier.padding(top = 8.dp, bottom = 8.dp, start = 12.dp, end = 12.dp) ,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceBright),
        elevation = CardDefaults.cardElevation(12.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 12.dp)
        ) {
            if (selectedImageUri != null) {
                Box(
                    modifier = Modifier
                        .width(120.dp)
                        .height(120.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                ) {
                    val context = LocalContext.current
                    AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(selectedImageUri)
                            .crossfade(true)
                            .build(),
                        contentDescription = "Selected image",
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                        placeholder = painterResource(id = R.drawable.placeholder)
                    )
                    IconButton(
                        onClick = onRemoveImage,
                        modifier = Modifier.align(Alignment.TopEnd)
                    ) {
                        Icon(imageVector = Icons.Filled.Close, contentDescription = "Remove image")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            BasicTextField(
                value = inputText,
                onValueChange = onInputChange,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 12.dp),
                textStyle = LocalTextStyle.current.copy(
                    fontSize = 14.sp,
                    color = MaterialTheme.colorScheme.onSurface
                ),
                cursorBrush = SolidColor(MaterialTheme.colorScheme.onSurfaceVariant),
                singleLine = false,
                maxLines = 4,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { onSend() }),
                decorationBox = { innerTextField ->
                    if (inputText.text.isEmpty()) {
                        Text(
                            text = "Explain this meme",
                            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
                            fontSize = 16.sp
                        )
                    }
                    innerTextField()
                }
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box {
                        Surface(
                            shape = RoundedCornerShape(8.dp),
                            color = MaterialTheme.colorScheme.surfaceVariant,
                            onClick = { showAttachMenu = true },
                        ) {
                            Row(
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 10.dp),
                                horizontalArrangement = Arrangement.spacedBy(6.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(
                                    imageVector = Icons.Filled.Add,
                                    contentDescription = "Attach",
                                    modifier = Modifier.size(18.dp),
                                    tint = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                        DropdownMenu(
                            expanded = showAttachMenu,
                            onDismissRequest = { showAttachMenu = false },
                            shape = RoundedCornerShape(12.dp),
                            offset = DpOffset(0.dp, (-4).dp),
                        ) {
                            DropdownMenuItem(
                                text = { Text("Image") },
                                onClick = {
                                    showAttachMenu = false
                                    onAttach()
                                },
                                leadingIcon = {
                                    Icon(Icons.Filled.Image, contentDescription = null)
                                }
                            )
                            DropdownMenuItem(
                                text = {
                                    Row(
                                        verticalAlignment = Alignment.CenterVertically,
                                        horizontalArrangement = Arrangement.spacedBy(4.dp),
                                    ) {
                                        Text(
                                            when (webSearchMode) {
                                                WebSearchMode.Search -> "Search Web"
                                                WebSearchMode.Fetch -> "Fetch URL"
                                                WebSearchMode.None -> "Web"
                                            }
                                        )
                                        Icon(
                                            imageVector = Icons.Filled.KeyboardArrowRight,
                                            contentDescription = null,
                                            modifier = Modifier.size(14.dp),
                                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                },
                                onClick = {
                                    onToggleWebSearchMode()
                                },
                                leadingIcon = {
                                    Icon(
                                        imageVector = when (webSearchMode) {
                                            WebSearchMode.None -> Icons.Filled.Close
                                            WebSearchMode.Search -> Icons.Filled.Search
                                            WebSearchMode.Fetch -> Icons.Filled.Language
                                        },
                                        contentDescription = null,
                                    )
                                }
                            )
                        }
                    }

                    ModelButton(
                        onClick = onToggleThinking,
                        icon = Icons.Filled.AutoAwesome,
                        text = "Thinking",
                        isHighlight = isThinkingEnabled
                    )
                }

                FloatingActionButton(
                    onClick = onSend,
                    containerColor = if (inputText.text.isNotEmpty()) {
                        MaterialTheme.colorScheme.primaryFixed
                    } else MaterialTheme.colorScheme.secondaryFixedDim,
                    modifier = Modifier.size(36.dp),
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Send,
                        contentDescription = "Send",
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
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
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
            Text(
                text = "Settings",
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.weight(1f))
            Icon(
                imageVector = Icons.Default.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(20.dp)
            )
        }
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
            MaterialTheme.colorScheme.primaryContainer
        } else MaterialTheme.colorScheme.surfaceVariant,
        shape = RoundedCornerShape(8.dp),
        border = if (isHighlight)
            BorderStroke(1.dp, MaterialTheme.colorScheme.primary)
        else BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f))
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
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(16.dp)
            )
            Text(
                text = text,
                fontSize = 13.sp,
                color = if (isHighlight) {
                    MaterialTheme.colorScheme.onPrimaryContainer
                } else MaterialTheme.colorScheme.onSurfaceVariant,
                fontWeight = if (isHighlight) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

@Composable
private fun LoopingWebmSnippet(
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val gifEnabledLoader = ImageLoader.Builder(context)
        .components {
            if ( SDK_INT >= 28 ) {
                add(ImageDecoderDecoder.Factory())
            } else {
                add(GifDecoder.Factory())
            }
        }.build()
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Image(
                modifier = Modifier.height(180.dp).width(180.dp).clip(RoundedCornerShape(16.dp)),
                painter = rememberAsyncImagePainter(
                    ImageRequest.Builder(context).data(data = R.drawable.scubaa).build(), imageLoader = gifEnabledLoader
                ),
                contentDescription = "Scuubaaaa",
            )
            Spacer(modifier = Modifier.height(24.dp))
            Text(
                text = "Hi, nerd!",
                fontSize = 24.sp,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = "What meme you got today?",
                fontSize = 16.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
            )
        }
    }
}

@Composable
private fun LoadingOverlay(
    modifier: Modifier = Modifier,
    loadingSize: Int
) {
    Box(
        modifier = modifier,
        contentAlignment = Alignment.Center
    ) {
        val composition by rememberLottieComposition(LottieCompositionSpec.RawRes(R.raw.lottieflow_loading))
        LottieAnimation(
            composition = composition,
            modifier = Modifier.size(loadingSize.dp),
            iterations = Int.MAX_VALUE
        )
    }
}

@Composable
private fun TransientErrorDialog(
    message: String,
    onDismiss: () -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Surface(
            shape = RoundedCornerShape(12.dp),
            color = MaterialTheme.colorScheme.errorContainer,
            tonalElevation = 2.dp
        ) {
            Text(
                text = message,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)
            )
        }
        IconButton(onClick = onDismiss, modifier = Modifier.align(Alignment.TopEnd)) {
            Icon(
                imageVector = Icons.Filled.Close,
                contentDescription = "Dismiss error",
                tint = MaterialTheme.colorScheme.onErrorContainer,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
fun CollapsibleReasoningSection(
    modifier: Modifier = Modifier,
    isStreaming: Boolean = false,
    title: Pair<String, String>,
    content: String,
    isExpanded: Boolean = true,
    onToggle: () -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        animationSpec = spring(stiffness = Spring.StiffnessMediumLow),
        label = "ChevronRotation"
    )

    Card(
        modifier = modifier
            .fillMaxWidth(fraction = 0.9f)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = LocalIndication.current,
                onClick = onToggle,
                role = Role.Button
            )
            .semantics { stateDescription = if (isExpanded) "Expand" else "Hide" },
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow
        ),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.elevatedCardElevation(1.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .animateContentSize(animationSpec = spring(stiffness = Spring.StiffnessMediumLow))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.End,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    if(isStreaming) {
                        LoadingOverlay(
                            modifier = Modifier
                                .width(60.dp)
                                .height(30.dp),
                            loadingSize = 30
                        )
                    }
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = if(isStreaming) title.first else title.second,
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                }

                Icon(
                    imageVector = Icons.Default.ExpandMore,
                    contentDescription = if (isExpanded) "Collapse $title" else "Expand $title",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier
                        .padding(start = 8.dp)
                        .graphicsLayer { rotationZ = chevronRotation }
                )
            }

            if (isExpanded) {
                VerticalDivider(
                    modifier = Modifier.padding(vertical = 4.dp),
                )
                MarkdownContent(
                    content = content,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 4.dp),
                    fontSize = MaterialTheme.typography.bodySmall.fontSize,
                )
            }
        }
    }
}


@Composable
private fun MarkdownContent(
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

@Preview(showBackground = true)
@Composable
private fun ChatScreenPreviewLight() {
    MaterialTheme {
        Surface {
            ChatScreenContent(
                uiState = ChatUiState(),
                messages = listOf(
                    ChatMessage(
                        id = "m1",
                        role = ChatRole.User,
                        text = "Hello MiniCPM!",
                        timestamp = "10:30",
                    ),
                    ChatMessage(
                        id = "m2",
                        role = ChatRole.Assistant,
                        text = "Hi there!",
                        timestamp = "10:31",
                    )
                ),
                inputText = TextFieldValue(""),
                onInputChange = {},
                drawerState = rememberDrawerState(DrawerValue.Closed),
                onOpenDrawer = {},
                onOpenSettings = {},
                conversations = emptyList(),
                selectedConversationId = "",
                onNewChat = {},
                onSelectConversation = {},
                onDeleteConversation = {},
                onAttach = {},
                onRemoveImage = {},
                onSend = {},
                onDismissError = {},
                isThinkingEnabled = false,
                onToggleThinking = {},
            )
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
            ChatScreenContent(
                uiState = ChatUiState(),
                messages = emptyList(),
                inputText = TextFieldValue(""),
                onInputChange = {},
                drawerState = rememberDrawerState(DrawerValue.Closed),
                onOpenDrawer = {},
                onOpenSettings = {},
                conversations = emptyList(),
                selectedConversationId = "",
                onNewChat = {},
                onSelectConversation = {},
                onDeleteConversation = {},
                onAttach = {},
                onRemoveImage = {},
                onSend = {},
                onDismissError = {},
                isThinkingEnabled = false,
                onToggleThinking = {},
            )
        }
    }
}

@Preview(name = "Collapsed State", showBackground = true, backgroundColor = 0xFFF8F9FA)
@Composable
private fun ReasoningSectionCollapsedPreview() {
    MaterialTheme {
        Box(modifier = Modifier.padding(16.dp)) {
            CollapsibleReasoningSection(
                title = Pair("Let me cook...", "Reasoning Process"),
                content = "This is the internal reasoning text that appears when expanded.",
                isExpanded = false
            ){}
        }
    }
}