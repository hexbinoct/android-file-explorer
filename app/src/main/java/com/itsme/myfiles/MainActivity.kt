package com.itsme.myfiles

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import com.itsme.myfiles.ui.theme.MyApplicationTheme
import java.io.File
import java.text.SimpleDateFormat
import java.util.*
import kotlin.math.log10
import kotlin.math.pow
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class ThemeMode { SYSTEM, LIGHT, DARK }

// Data model for files and folders
data class FileItem(
    val file: File,
    val name: String,
    val isDirectory: Boolean,
    val size: String,
    val lastModified: String
)

class FileViewModel(application: Application) : AndroidViewModel(application) {
    private val prefs: SharedPreferences = application.getSharedPreferences("settings_prefs", Context.MODE_PRIVATE)

    class PaneState(initialPath: File) {
        var currentPath by mutableStateOf(initialPath)
        var files by mutableStateOf<List<FileItem>>(emptyList())
        var selectedItem by mutableStateOf<FileItem?>(null)
    }

    var isSplitMode by mutableStateOf(false)
    var splitRatio by mutableFloatStateOf(0.5f) // 0 to 1
    val pane1 = PaneState(Environment.getExternalStorageDirectory())
    val pane2 = PaneState(Environment.getExternalStorageDirectory())
    var activePaneIndex by mutableIntStateOf(1)

    var showSettings by mutableStateOf(false)
    
    val itemCounts = mutableStateMapOf<String, Int?>()
    val scrollPositions = mutableStateMapOf<String, Pair<Int, Int>>()

    // Persistent settings loaded from SharedPreferences
    var nameFontSize by mutableFloatStateOf(prefs.getFloat("nameFontSize", 16f))
    var infoFontSize by mutableFloatStateOf(prefs.getFloat("infoFontSize", 11f))
    var folderColor by mutableStateOf(Color(prefs.getInt("folderColor", Color(0xFFFFB300).toArgb())))
    var themeMode by mutableStateOf(ThemeMode.valueOf(prefs.getString("themeMode", ThemeMode.SYSTEM.name) ?: ThemeMode.SYSTEM.name))
    var showDivider by mutableStateOf(prefs.getBoolean("showDivider", true))
    var itemSpacing by mutableFloatStateOf(prefs.getFloat("itemSpacing", 4f))
    var showItemCount by mutableStateOf(prefs.getBoolean("showItemCount", false))

    fun updateSetting(action: SharedPreferences.Editor.() -> Unit) {
        prefs.edit().apply {
            action()
            apply()
        }
    }

    fun loadFiles(path: File, pane: PaneState, saveScrollIndex: Int? = null, saveScrollOffset: Int? = null) {
        if (saveScrollIndex != null && saveScrollOffset != null) {
            scrollPositions[pane.currentPath.absolutePath] = saveScrollIndex to saveScrollOffset
        }
        pane.currentPath = path
        pane.selectedItem = null
        val list = path.listFiles()?.map {
            FileItem(
                it,
                it.name,
                it.isDirectory,
                if (it.isDirectory) "" else formatSize(it.length()),
                formatDate(it.lastModified())
            )
        }?.sortedWith(compareBy({ !it.isDirectory }, { it.name.lowercase() })) ?: emptyList()
        pane.files = list
    }

    fun copyFile(source: File, targetDir: File, onComplete: (Boolean) -> Unit) {
        viewModelScope.launch(Dispatchers.IO) {
            val success = try {
                val dest = File(targetDir, source.name)
                if (source.isDirectory) {
                    source.copyRecursively(dest, overwrite = true)
                } else {
                    source.copyTo(dest, overwrite = true)
                }
                true
            } catch (e: Exception) {
                false
            }
            withContext(Dispatchers.Main) {
                onComplete(success)
                // Refresh both panes just in case
                loadFiles(pane1.currentPath, pane1)
                loadFiles(pane2.currentPath, pane2)
            }
        }
    }

    private fun formatSize(bytes: Long): String {
        if (bytes <= 0) return "0 B"
        val units = arrayOf("B", "KB", "MB", "GB", "TB")
        val digitGroups = (log10(bytes.toDouble()) / log10(1024.0)).toInt()
        return "%.1f %s".format(bytes / 1024.0.pow(digitGroups.toDouble()), units[digitGroups])
    }

    private fun formatDate(timestamp: Long): String {
        val sdf = SimpleDateFormat("MMM dd, HH:mm", Locale.getDefault())
        return sdf.format(Date(timestamp))
    }

    fun canNavigateBack(pane: PaneState): Boolean {
        val root = Environment.getExternalStorageDirectory().absolutePath
        return pane.currentPath.absolutePath != root && pane.currentPath.absolutePath != "/"
    }

    fun navigateBack(pane: PaneState, saveScrollIndex: Int? = null, saveScrollOffset: Int? = null) {
        if (saveScrollIndex != null && saveScrollOffset != null) {
            scrollPositions[pane.currentPath.absolutePath] = saveScrollIndex to saveScrollOffset
        }
        val parent = pane.currentPath.parentFile
        if (parent != null && canNavigateBack(pane)) {
            loadFiles(parent, pane)
        }
    }
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            val viewModel: FileViewModel = viewModel()
            val isDark = when (viewModel.themeMode) {
                ThemeMode.SYSTEM -> isSystemInDarkTheme()
                ThemeMode.LIGHT -> false
                ThemeMode.DARK -> true
            }
            
            MyApplicationTheme(darkTheme = isDark) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    FileExplorerApp(viewModel)
                }
            }
        }
    }
}

@Composable
fun FileExplorerApp(viewModel: FileViewModel) {
    val context = LocalContext.current
    var hasPermission by remember { mutableStateOf(checkStoragePermission(context)) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasPermission = isGranted
    }

    LaunchedEffect(hasPermission) {
        if (hasPermission) {
            viewModel.loadFiles(viewModel.pane1.currentPath, viewModel.pane1)
            if (viewModel.isSplitMode) {
                viewModel.loadFiles(viewModel.pane2.currentPath, viewModel.pane2)
            }
        }
    }

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                hasPermission = checkStoragePermission(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    if (!hasPermission) {
        PermissionRequestScreen {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION)
                    intent.data = Uri.parse("package:${context.packageName}")
                    context.startActivity(intent)
                } catch (e: Exception) {
                    val intent = Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
                    context.startActivity(intent)
                }
            } else {
                permissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }
    } else {
        if (viewModel.showSettings) {
            SettingsScreen(viewModel)
        } else {
            FileScanner(viewModel)
            BoxWithConstraints(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                val maxHeight = constraints.maxHeight.toFloat()
                Column(modifier = Modifier.fillMaxSize()) {
                    val p1Weight = if (viewModel.isSplitMode) viewModel.splitRatio else 1f
                    
                    Box(modifier = Modifier.weight(p1Weight)) {
                        FileBrowser(viewModel, viewModel.pane1, isPrimary = true)
                    }
                    
                    if (viewModel.isSplitMode) {
                        // Draggable Divider
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(8.dp)
                                .background(MaterialTheme.colorScheme.outlineVariant)
                                .pointerInput(Unit) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        val delta = dragAmount.y / maxHeight
                                        viewModel.splitRatio = (viewModel.splitRatio + delta).coerceIn(0.1f, 0.9f)
                                    }
                                }
                        )
                        
                        Box(modifier = Modifier.weight(1f - viewModel.splitRatio)) {
                            FileBrowser(viewModel, viewModel.pane2, isPrimary = false)
                        }
                    }
                }
            }
        }
    }
}

fun checkStoragePermission(context: Context): Boolean {
    return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
        Environment.isExternalStorageManager()
    } else {
        ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.READ_EXTERNAL_STORAGE
        ) == PackageManager.PERMISSION_GRANTED
    }
}

@Composable
fun PermissionRequestScreen(onRequest: () -> Unit) {
    Box(modifier = Modifier.fillMaxSize().padding(32.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Storage access is required to show your files.", style = MaterialTheme.typography.bodyLarge)
            Spacer(modifier = Modifier.height(24.dp))
            Button(onClick = onRequest) {
                Text("Grant Permission")
            }
        }
    }
}

@Composable
fun FileScanner(viewModel: FileViewModel) {
    LaunchedEffect(viewModel.pane1.files, viewModel.pane2.files, viewModel.isSplitMode, viewModel.showItemCount) {
        if (!viewModel.showItemCount) return@LaunchedEffect
        
        val allFiles = mutableListOf<FileItem>()
        allFiles.addAll(viewModel.pane1.files)
        if (viewModel.isSplitMode) {
            allFiles.addAll(viewModel.pane2.files)
        }
        allFiles.forEach { item ->
            if (item.isDirectory && !viewModel.itemCounts.containsKey(item.file.absolutePath)) {
                launch(Dispatchers.IO) {
                    val count = try {
                        item.file.listFiles()?.size ?: 0
                    } catch (e: Exception) {
                        0
                    }
                    withContext(Dispatchers.Main) {
                        viewModel.itemCounts[item.file.absolutePath] = count
                    }
                }
            }
        }
    }
}

@Composable
fun FileBrowser(
    viewModel: FileViewModel,
    pane: FileViewModel.PaneState,
    isPrimary: Boolean,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val listState = remember(pane.currentPath.absolutePath) {
        val savedPos = viewModel.scrollPositions[pane.currentPath.absolutePath]
        LazyListState(
            firstVisibleItemIndex = savedPos?.first ?: 0,
            firstVisibleItemScrollOffset = savedPos?.second ?: 0
        )
    }

    val isActive = if (viewModel.isSplitMode) {
        (isPrimary && viewModel.activePaneIndex == 1) || (!isPrimary && viewModel.activePaneIndex == 2)
    } else {
        isPrimary
    }

    BackHandler(enabled = isActive && viewModel.canNavigateBack(pane)) {
        viewModel.navigateBack(pane, listState.firstVisibleItemIndex, listState.firstVisibleItemScrollOffset)
    }

    Column(modifier = modifier.fillMaxSize()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = pane.currentPath.path,
                fontSize = 10.sp,
                color = if (isActive) MaterialTheme.colorScheme.primary else Color.Gray,
                modifier = Modifier.weight(1f).padding(start = 4.dp),
                maxLines = 1
            )
            
            var showMenu by remember { mutableStateOf(false) }
            Box {
                IconButton(onClick = { showMenu = true }, modifier = Modifier.size(24.dp)) {
                    Icon(
                        Icons.Default.MoreVert,
                        contentDescription = "Menu",
                        modifier = Modifier.size(16.dp),
                        tint = Color.Gray.copy(alpha = 0.6f)
                    )
                }
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false }
                ) {
                    DropdownMenuItem(
                        text = { Text("Settings") },
                        onClick = {
                            showMenu = false
                            viewModel.showSettings = true
                        }
                    )
                    
                    if (isPrimary) {
                        DropdownMenuItem(
                            text = {
                                Text(if (viewModel.isSplitMode) "Close Split View" else "Split Window")
                            },
                            onClick = {
                                showMenu = false
                                if (viewModel.isSplitMode) {
                                    viewModel.isSplitMode = false
                                } else {
                                    viewModel.isSplitMode = true
                                    viewModel.loadFiles(viewModel.pane2.currentPath, viewModel.pane2)
                                }
                            }
                        )
                    }

                    if (viewModel.isSplitMode && pane.selectedItem != null) {
                        val otherPane = if (isPrimary) viewModel.pane2 else viewModel.pane1
                        val actionText = if (isPrimary) "Copy to Lower View" else "Copy to Upper View"
                        DropdownMenuItem(
                            text = { Text(actionText) },
                            onClick = {
                                showMenu = false
                                viewModel.copyFile(pane.selectedItem!!.file, otherPane.currentPath) { success ->
                                    Toast.makeText(context, if (success) "Copied successfully" else "Copy failed", Toast.LENGTH_SHORT).show()
                                }
                            }
                        )
                    }
                }
            }
        }

        if (pane.files.isEmpty()) {
            Box(modifier = Modifier.fillMaxSize().weight(1f).clickable { 
                if (isPrimary) viewModel.activePaneIndex = 1 else viewModel.activePaneIndex = 2
            }, contentAlignment = Alignment.Center) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Icon(
                        Icons.Default.Folder,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = Color.Gray.copy(alpha = 0.3f)
                    )
                    Text("Empty folder", color = Color.Gray)
                }
            }
        } else {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().weight(1f),
                contentPadding = PaddingValues(bottom = 16.dp)
            ) {
                items(pane.files, key = { it.file.absolutePath }) { item ->
                    FileRow(
                        item = item,
                        isSelected = pane.selectedItem == item,
                        nameFontSize = viewModel.nameFontSize,
                        infoFontSize = viewModel.infoFontSize,
                        folderColor = viewModel.folderColor,
                        itemSpacing = viewModel.itemSpacing,
                        itemCount = viewModel.itemCounts[item.file.absolutePath],
                        showItemCount = viewModel.showItemCount,
                        onFolderClick = {
                            if (isPrimary) viewModel.activePaneIndex = 1 else viewModel.activePaneIndex = 2
                            viewModel.loadFiles(
                                item.file,
                                pane,
                                listState.firstVisibleItemIndex,
                                listState.firstVisibleItemScrollOffset
                            )
                        },
                        onClick = {
                            if (isPrimary) viewModel.activePaneIndex = 1 else viewModel.activePaneIndex = 2
                            pane.selectedItem = if (pane.selectedItem == item) null else item
                        }
                    )
                    if (viewModel.showDivider) {
                        HorizontalDivider(
                            modifier = Modifier.padding(horizontal = 16.dp),
                            thickness = 0.5.dp,
                            color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                        )
                    }
                }
            }
        }
    }
}

@Composable
fun FileRow(
    item: FileItem,
    isSelected: Boolean,
    nameFontSize: Float,
    infoFontSize: Float,
    folderColor: Color,
    itemSpacing: Float,
    itemCount: Int?,
    showItemCount: Boolean,
    onFolderClick: () -> Unit,
    onClick: () -> Unit = {}
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(if (isSelected) MaterialTheme.colorScheme.primaryContainer else Color.Transparent)
            .clickable {
                onClick()
                // If it's a folder, we might want to navigate on double click or just selection?
                // The prompt says "once any item is selected". 
                // Let's keep clicking for selection, and navigate on double click or just add a 'Open' button?
                // Actually, the previous version navigated on click. 
                // To support selection, I'll make the first click select, and if it's already selected and is a folder, navigate.
                if (isSelected && item.isDirectory) {
                    onFolderClick()
                }
            }
            .padding(horizontal = 16.dp, vertical = itemSpacing.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = if (item.isDirectory) Icons.Default.Folder else Icons.Default.Description,
            contentDescription = null,
            tint = if (item.isDirectory) folderColor else Color.Gray,
            modifier = Modifier.size((nameFontSize * 1.5).dp)
        )
        Spacer(modifier = Modifier.width(16.dp))
        
        Row(
            modifier = Modifier.weight(1f),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(
                text = item.name,
                fontSize = nameFontSize.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier.weight(1f, fill = false)
            )
            
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(horizontalAlignment = Alignment.End) {
                if (item.isDirectory) {
                    if (showItemCount) {
                        Text(
                            text = if (itemCount != null) "$itemCount items" else "counting...",
                            fontSize = infoFontSize.sp,
                            color = Color.Gray,
                            textAlign = TextAlign.End
                        )
                    }
                    Text(
                        text = item.lastModified,
                        fontSize = infoFontSize.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.End
                    )
                } else {
                    Text(
                        text = item.size,
                        fontSize = infoFontSize.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.End
                    )
                    Text(
                        text = item.lastModified,
                        fontSize = infoFontSize.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.End
                    )
                }
            }
        }
    }
}

@Composable
fun SettingsScreen(viewModel: FileViewModel) {
    BackHandler {
        viewModel.showSettings = false
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .padding(16.dp)
            .verticalScroll(rememberScrollState())
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Settings", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            Button(onClick = { viewModel.showSettings = false }) {
                Text("Done")
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Text("Appearance", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        // Theme Mode
        Text("Theme Mode")
        Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceEvenly) {
            ThemeMode.entries.forEach { mode ->
                FilterChip(
                    selected = viewModel.themeMode == mode,
                    onClick = {
                        viewModel.themeMode = mode
                        viewModel.updateSetting { putString("themeMode", mode.name) }
                    },
                    label = { Text(mode.name.lowercase().replaceFirstChar { it.uppercase() }) }
                )
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Folder Color
        Text("Folder Icon Color")
        Row(modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.SpaceBetween) {
            val colors = listOf(Color(0xFFFFB300), Color(0xFF42A5F5), Color(0xFF66BB6A), Color(0xFFEF5350), Color(0xFFAB47BC))
            colors.forEach { color ->
                Box(
                    modifier = Modifier
                        .size(40.dp)
                        .clip(CircleShape)
                        .background(color)
                        .clickable {
                            viewModel.folderColor = color
                            viewModel.updateSetting { putInt("folderColor", color.toArgb()) }
                        }
                        .padding(4.dp)
                ) {
                    if (viewModel.folderColor == color) {
                        Box(modifier = Modifier.fillMaxSize().clip(CircleShape).background(Color.White.copy(alpha = 0.3f)))
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
        Text("Layout", fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
        Spacer(modifier = Modifier.height(16.dp))

        // Font Sizes
        Text("File Name Font Size: ${viewModel.nameFontSize.toInt()}sp")
        Slider(
            value = viewModel.nameFontSize,
            onValueChange = { 
                viewModel.nameFontSize = it
                viewModel.updateSetting { putFloat("nameFontSize", it) }
            },
            valueRange = 12f..24f
        )

        Text("Info Font Size: ${viewModel.infoFontSize.toInt()}sp")
        Slider(
            value = viewModel.infoFontSize,
            onValueChange = { 
                viewModel.infoFontSize = it
                viewModel.updateSetting { putFloat("infoFontSize", it) }
            },
            valueRange = 8f..16f
        )

        // Item Spacing
        Text("Item Vertical Spacing: ${viewModel.itemSpacing.toInt()}dp")
        Slider(
            value = viewModel.itemSpacing,
            onValueChange = { 
                viewModel.itemSpacing = it
                viewModel.updateSetting { putFloat("itemSpacing", it) }
            },
            valueRange = 0f..24f
        )

        // Show Divider
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show Item Dividers", modifier = Modifier.weight(1f))
            Switch(
                checked = viewModel.showDivider,
                onCheckedChange = { 
                    viewModel.showDivider = it
                    viewModel.updateSetting { putBoolean("showDivider", it) }
                }
            )
        }

        // Show Item Count
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Show Folder Item Count", modifier = Modifier.weight(1f))
            Switch(
                checked = viewModel.showItemCount,
                onCheckedChange = { 
                    viewModel.showItemCount = it
                    viewModel.updateSetting { putBoolean("showItemCount", it) }
                }
            )
        }
    }
}
