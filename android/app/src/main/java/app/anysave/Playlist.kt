package app.anysave

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage

@Composable
fun PlaylistPicker(
    entries: List<PlaylistEntry>,
    picked: Set<Int>,
    onToggle: (Int) -> Unit,
    onAll: (Boolean) -> Unit,
    maxHeight: Dp = 320.dp,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Videos", style = MaterialTheme.typography.labelLarge)
        Spacer(Modifier.width(8.dp))
        Text("${picked.size} of ${entries.size} selected", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f))
        TextButton(onClick = { onAll(true) }) { Text("All") }
        TextButton(onClick = { onAll(false) }) { Text("None") }
    }
    Surface(shape = RoundedCornerShape(12.dp), tonalElevation = 2.dp, modifier = Modifier.fillMaxWidth()) {
        LazyColumn(Modifier.heightIn(max = maxHeight)) {
            itemsIndexed(entries, key = { i, e -> "$i ${e.url}" }) { i, e ->
                Row(Modifier.fillMaxWidth().clickable { onToggle(i) }.padding(horizontal = 4.dp, vertical = 2.dp),
                    verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = i in picked, onCheckedChange = { onToggle(i) })
                    Text("${i + 1}", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(24.dp))
                    if (e.thumbnail != null) {
                        AsyncImage(e.thumbnail, null, Modifier.width(64.dp).aspectRatio(16f / 9f).clip(RoundedCornerShape(6.dp)),
                            contentScale = ContentScale.Crop)
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(e.title, maxLines = 2, overflow = TextOverflow.Ellipsis, fontSize = 14.sp, modifier = Modifier.weight(1f))
                    formatDuration(e.duration).takeIf { it.isNotBlank() }?.let {
                        Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(start = 6.dp, end = 8.dp))
                    }
                }
            }
        }
    }
}

@Composable
fun PlaylistBanner(text: String, action: String, onClick: () -> Unit) {
    Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.secondaryContainer, modifier = Modifier.fillMaxWidth()) {
        Row(Modifier.padding(start = 12.dp, end = 4.dp, top = 2.dp, bottom = 2.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(text, fontSize = 13.sp, modifier = Modifier.weight(1f))
            TextButton(onClick = onClick) { Text(action) }
        }
    }
}
