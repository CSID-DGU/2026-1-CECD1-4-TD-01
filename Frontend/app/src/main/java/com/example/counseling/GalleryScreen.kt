package com.example.counseling

import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun GalleryScreen(presentationMode: Boolean = false) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var images by remember { mutableStateOf<List<GalleryImage>>(emptyList()) }
    var message by remember { mutableStateOf("사진 권한을 허용하면 갤러리 이미지를 불러오고 분석 문서를 만듭니다.") }
    var access by remember { mutableStateOf(galleryAccess(context)) }
    var cache by remember { mutableStateOf<GalleryAnalysisCacheSnapshot?>(null) }
    var showPermissionDialog by remember { mutableStateOf(false) }
    var showAnalysisDialog by remember { mutableStateOf(false) }
    var isLoadingGallery by remember { mutableStateOf(false) }
    var isAnalyzingGallery by remember { mutableStateOf(false) }

    fun loadImages(forceAnalysis: Boolean = false) {
        scope.launch {
            isLoadingGallery = true
            isAnalyzingGallery = false
            try {
                access = galleryAccess(context)
                message = "Gallery 이미지를 불러오는 중..."
                images = queryGalleryImages(context)
                val accessLabel = if (access == GalleryAccess.Partial) "선택한 사진" else "전체 사진"
                if (images.isEmpty()) {
                    cache = readGalleryAnalysisCache(context)
                    message = "불러올 수 있는 이미지가 없습니다."
                } else {
                    message = "$accessLabel 기준으로 이미지 ${images.size}장을 불러왔습니다."
                    isAnalyzingGallery = true
                    cache = refreshGalleryAnalysisCacheIfNeeded(
                        context = context,
                        images = images,
                        force = forceAnalysis,
                        onProgress = { progressMessage -> message = progressMessage },
                    )
                    message = "$accessLabel 기준으로 이미지 ${images.size}장을 불러왔고 Gallery 분석 문서를 최신 상태로 맞췄습니다."
                }
            } finally {
                isLoadingGallery = false
                isAnalyzingGallery = false
            }
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions(),
        onResult = {
            access = galleryAccess(context)
            if (access != GalleryAccess.None) {
                loadImages()
            } else {
                message = "사진 권한을 허용하지 않았습니다. 시스템 권한 설정에서 사진 접근을 허용해 주세요."
            }
        },
    )

    if (showPermissionDialog) {
        AlertDialog(
            onDismissRequest = { showPermissionDialog = false },
            title = { Text("사진 접근 권한") },
            text = {
                Text("전체 사진을 불러오거나 선택한 사진 목록만 다시 지정할 수 있습니다. 권한을 줄이거나 완전히 회수하려면 권한 설정을 여세요.")
            },
            confirmButton = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            showPermissionDialog = false
                            permissionLauncher.launch(fullGalleryPermissionsForDevice())
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("전체 사진 권한 요청")
                    }
                    OutlinedButton(
                        onClick = {
                            showPermissionDialog = false
                            permissionLauncher.launch(partialGalleryPermissionsForDevice())
                        },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("선택한 사진만 사용")
                    }
                    OutlinedButton(
                        onClick = { showPermissionDialog = false },
                        shape = RoundedCornerShape(8.dp),
                    ) {
                        Text("닫기")
                    }
                }
            },
        )
    }

    if (showAnalysisDialog) {
        if (presentationMode) {
            GalleryUserAnalysisDialog(
                cache = cache,
                onDismiss = { showAnalysisDialog = false },
            )
        } else {
            GalleryAnalysisDialog(
                cache = cache,
                onDismiss = { showAnalysisDialog = false },
            )
        }
    }

    LaunchedEffect(Unit) {
        access = galleryAccess(context)
        cache = readGalleryAnalysisCache(context)
        if (access != GalleryAccess.None) loadImages()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Button(
                onClick = {
                    access = galleryAccess(context)
                    if (access == GalleryAccess.None) showPermissionDialog = true else loadImages()
                },
                enabled = !isLoadingGallery && !isAnalyzingGallery,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(
                    when (access) {
                        GalleryAccess.None -> "사진 권한"
                        GalleryAccess.Partial -> "사진 추가"
                        GalleryAccess.Full -> "새로고침"
                    },
                )
            }
            OutlinedButton(
                onClick = { openAppSettings(context) },
                enabled = !isLoadingGallery && !isAnalyzingGallery,
                shape = RoundedCornerShape(8.dp),
            ) {
                Text(if (access == GalleryAccess.Full) "권한 줄이기" else "권한 설정")
            }
        }

        if (isLoadingGallery || isAnalyzingGallery) {
            BusyStatusRow(
                title = if (isAnalyzingGallery) "이미지 분석 중" else "갤러리 불러오는 중",
                message = message,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
            )
        }

        GalleryAnalysisSummaryCard(
            cache = cache,
            enabled = access != GalleryAccess.None,
            busy = isLoadingGallery || isAnalyzingGallery,
            presentationMode = presentationMode,
            onShow = { showAnalysisDialog = true },
            onRefresh = { loadImages(forceAnalysis = true) },
        )

        LazyVerticalGrid(
            columns = GridCells.Adaptive(112.dp),
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(8.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(images, key = { it.uri.toString() }) { image ->
                GalleryTile(image)
            }
        }
    }
}

@Composable
private fun GalleryAnalysisSummaryCard(
    cache: GalleryAnalysisCacheSnapshot?,
    enabled: Boolean,
    busy: Boolean,
    presentationMode: Boolean,
    onShow: () -> Unit,
    onRefresh: () -> Unit,
) {
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        shape = RoundedCornerShape(8.dp),
        tonalElevation = 1.dp,
    ) {
        Column(modifier = Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                if (presentationMode) "내 갤러리 관심사" else "Gallery 분석 문서",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                text = if (cache == null) {
                    if (presentationMode) {
                        "사진 권한을 허용하면 사진 속 활동과 관심사를 간단히 정리해 보여줍니다."
                    } else {
                        "아직 저장된 분석 문서가 없습니다. 사진 권한을 허용하고 Gallery를 불러오면 자동으로 생성됩니다."
                    }
                } else {
                    "이미지 ${cache.imageCount}장 기준 · ${formatGalleryCacheTime(cache.updatedAt)} 갱신"
                },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (presentationMode && cache != null) {
                UserInterestPreview(cache.interestItems)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(
                    onClick = onShow,
                    enabled = cache != null && !busy,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text(if (presentationMode) "관심사 자세히" else "분석 결과 보기")
                }
                OutlinedButton(
                    onClick = onRefresh,
                    enabled = enabled && !busy,
                    shape = RoundedCornerShape(8.dp),
                ) {
                    Text("다시 분석")
                }
            }
        }
    }
}

@Composable
private fun GalleryAnalysisDialog(
    cache: GalleryAnalysisCacheSnapshot?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Gallery 분석 결과") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (cache == null) {
                    Text("저장된 분석 문서가 없습니다.")
                } else {
                    Text(
                        "이미지 ${cache.imageCount}장 기준 · ${formatGalleryCacheTime(cache.updatedAt)} 갱신",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(cache.contextText, style = MaterialTheme.typography.bodySmall)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("닫기")
            }
        },
    )
}

@Composable
private fun GalleryUserAnalysisDialog(
    cache: GalleryAnalysisCacheSnapshot?,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("갤러리 관심사") },
        text = {
            val scroll = rememberScrollState()
            Column(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(scroll),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                if (cache == null) {
                    Text("아직 정리된 관심사가 없습니다.")
                } else {
                    Text(
                        "이미지 ${cache.imageCount}장 기준 · ${formatGalleryCacheTime(cache.updatedAt)} 갱신",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        "사진 속에 반복해서 보이는 활동을 기준으로 가볍게 추정한 결과입니다.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    UserInterestList(cache.interestItems)
                }
            }
        },
        confirmButton = {
            Button(onClick = onDismiss, shape = RoundedCornerShape(8.dp)) {
                Text("닫기")
            }
        },
    )
}

@Composable
private fun UserInterestPreview(items: List<GalleryInterestItem>) {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (items.isEmpty()) {
            Text(
                "관심사 요약을 만들려면 다시 분석을 눌러 주세요.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            items
                .sortedByDescending { it.ratio * 0.7 + it.confidence * 0.3 }
                .take(3)
                .forEach { item ->
                    UserInterestRow(item = item, compact = true)
                }
        }
    }
}

@Composable
private fun UserInterestList(items: List<GalleryInterestItem>) {
    val sorted = items.sortedByDescending { it.ratio * 0.7 + it.confidence * 0.3 }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (sorted.isEmpty()) {
            Text(
                "이전 형식의 분석 결과입니다. 갤러리 화면에서 다시 분석하면 관심사 요약을 볼 수 있습니다.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        } else {
            sorted.forEach { item ->
                UserInterestRow(item = item, compact = false)
            }
        }
    }
}

@Composable
private fun UserInterestRow(item: GalleryInterestItem, compact: Boolean) {
    val progress = ((item.ratio * 0.7) + (item.confidence * 0.3)).coerceIn(0.0, 1.0).toFloat()
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = if (compact) 9.dp else 12.dp),
            verticalArrangement = Arrangement.spacedBy(7.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    item.label,
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                Text(
                    item.level,
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                    fontWeight = FontWeight.SemiBold,
                )
            }
            LinearProgressIndicator(
                progress = { progress },
                modifier = Modifier.fillMaxWidth(),
            )
            if (!compact) {
                Text(
                    "사진 ${item.photoCount}장 · 활동 ${item.eventCount}회 · 비중 ${(item.ratio * 100).toInt()}%",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
}

@Composable
fun GalleryTile(image: GalleryImage) {
    val context = LocalContext.current
    var bitmap by remember(image.uri) { mutableStateOf<Bitmap?>(null) }

    LaunchedEffect(image.uri) {
        bitmap = loadThumbnail(context, image.uri)
    }

    Surface(shape = RoundedCornerShape(8.dp), tonalElevation = 1.dp) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            contentAlignment = Alignment.Center,
        ) {
            val loaded = bitmap
            if (loaded == null) {
                CircularProgressIndicator(modifier = Modifier.size(28.dp))
            } else {
                Image(
                    bitmap = loaded.asImageBitmap(),
                    contentDescription = image.name,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            }
        }
    }
}

private fun formatGalleryCacheTime(updatedAt: Long): String {
    return DateTimeFormatter.ofPattern("MM.dd HH:mm")
        .format(Instant.ofEpochMilli(updatedAt).atZone(ZoneId.systemDefault()))
}
