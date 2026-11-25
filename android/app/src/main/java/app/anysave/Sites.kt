package app.anysave

import androidx.compose.ui.graphics.Color
import java.net.URI

data class Site(
    val id: String,
    val name: String,
    val color: Color,
    val subtitle: String,
    val placeholder: String,
    val hosts: List<String>,
    val tips: List<String>,
)

val SITES = listOf(
    Site("all", "All sites", Color(0xFF6D5EFC), "Paste a link from any supported site",
        "Paste a video link", emptyList(),
        listOf("Instagram, Facebook, TikTok, X, Bluesky, LinkedIn, Reddit, Pinterest, YouTube and 1,000+ more",
            "Tip: in any app tap Share › AnySave")),
    Site("instagram", "Instagram", Color(0xFFD62976), "Reels and video posts from public accounts",
        "https://www.instagram.com/reel/…", listOf("instagram.com", "instagr.am"),
        listOf("Tap Share › AnySave, or Copy link and paste here",
            "Stories and private accounts need a login and won't work",
            "Carousels download the first video")),
    Site("facebook", "Facebook", Color(0xFF1877F2), "Public videos, Reels and Watch",
        "https://www.facebook.com/reel/…", listOf("facebook.com", "fb.watch", "fb.com"),
        listOf("Tap Share, then Copy link", "fb.watch short links work",
            "Private groups and friends-only posts won't work")),
    Site("tiktok", "TikTok", Color(0xFFFE2C55), "Short videos",
        "https://www.tiktok.com/@user/video/…", listOf("tiktok.com"),
        listOf("Tap Share, then Copy link", "vm.tiktok.com short links work", "Photo slideshows aren't videos")),
    Site("x", "X / Twitter", Color(0xFF1D9BF0), "Videos and GIFs in public posts",
        "https://x.com/user/status/…", listOf("x.com", "twitter.com", "t.co"),
        listOf("Open the post, tap Share, then Copy link", "Posts from protected accounts won't work")),
    Site("bluesky", "Bluesky", Color(0xFF1185FE), "Videos in posts",
        "https://bsky.app/profile/…/post/…", listOf("bsky.app"),
        listOf("Tap ⋯ on a post, then Copy link to post")),
    Site("linkedin", "LinkedIn", Color(0xFF0A66C2), "Videos in public posts",
        "https://www.linkedin.com/posts/…", listOf("linkedin.com", "lnkd.in"),
        listOf("Tap ⋯ on the post, then Copy link to post",
            "Connections-only posts and LinkedIn Learning won't work")),
    Site("reddit", "Reddit", Color(0xFFFF4500), "Video posts, with sound merged back in",
        "https://www.reddit.com/r/…/comments/…", listOf("reddit.com", "redd.it"),
        listOf("Tap Share, then Copy link", "Sound is stored separately; the server joins it back")),
    Site("pinterest", "Pinterest", Color(0xFFE60023), "Video pins and Idea pins",
        "https://www.pinterest.com/pin/…",
        listOf("pinterest.com", "pin.it", "pinterest.co.uk", "pinterest.ca", "pinterest.de", "pinterest.fr",
            "pinterest.es", "pinterest.it", "pinterest.com.au", "pinterest.jp", "pinterest.co.in"),
        listOf("Tap Share, then Copy link", "pin.it short links work", "Image-only pins have no video")),
    Site("youtube", "YouTube", Color(0xFFE62117), "Videos and Shorts, up to 4K/8K",
        "https://www.youtube.com/watch?v=…", listOf("youtube.com", "youtu.be"),
        listOf("Tap Share, then Copy link", "A video inside a playlist downloads just that video",
            "Members-only and private videos won't work")),
    Site("whatsapp", "WhatsApp", Color(0xFF25D366), "Status saver",
        "", listOf("whatsapp.com", "wa.me"), emptyList()),
)

fun siteById(id: String): Site = SITES.firstOrNull { it.id == id } ?: SITES.first()

fun siteForUrl(url: String): Site? {
    val host = try {
        URI(url.trim()).host?.lowercase()
    } catch (e: Exception) {
        null
    } ?: return null
    return SITES.firstOrNull { s -> s.hosts.any { host == it || host.endsWith(".$it") } }
}

private val LINK = Regex("""https?://\S+""")

fun firstLink(text: String?): String? = text?.let { LINK.find(it)?.value?.trimEnd('.', ',', ')', '"', '\'') }
