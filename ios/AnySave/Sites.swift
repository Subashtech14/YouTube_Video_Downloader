import SwiftUI

struct Site: Identifiable, Hashable {
    let id: String
    let name: String
    let hex: UInt32
    let subtitle: String
    let placeholder: String
    let hosts: [String]
    let tips: [String]

    var color: Color {
        Color(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
    }
}

let sites: [Site] = [
    Site(id: "all", name: "All sites", hex: 0x6D5EFC, subtitle: "Paste a link from any supported site",
         placeholder: "Paste a video link", hosts: [],
         tips: ["Instagram, Facebook, TikTok, X, Bluesky, LinkedIn, Reddit, Pinterest, YouTube and 1,000+ more",
                "Pasting a link here jumps to the right tab"]),
    Site(id: "instagram", name: "Instagram", hex: 0xD62976, subtitle: "Reels and video posts from public accounts",
         placeholder: "https://www.instagram.com/reel/…", hosts: ["instagram.com", "instagr.am"],
         tips: ["Tap ⋯ on a Reel or post, then Copy link",
                "Stories and private accounts need a login and won't work",
                "Carousels download the first video"]),
    Site(id: "facebook", name: "Facebook", hex: 0x1877F2, subtitle: "Public videos, Reels and Watch",
         placeholder: "https://www.facebook.com/reel/…", hosts: ["facebook.com", "fb.watch", "fb.com"],
         tips: ["Tap Share, then Copy link", "fb.watch short links work",
                "Private groups and friends-only posts won't work"]),
    Site(id: "tiktok", name: "TikTok", hex: 0xFE2C55, subtitle: "Short videos",
         placeholder: "https://www.tiktok.com/@user/video/…", hosts: ["tiktok.com"],
         tips: ["Tap Share, then Copy link", "vm.tiktok.com short links work", "Photo slideshows aren't videos"]),
    Site(id: "x", name: "X / Twitter", hex: 0x1D9BF0, subtitle: "Videos and GIFs in public posts",
         placeholder: "https://x.com/user/status/…", hosts: ["x.com", "twitter.com", "t.co"],
         tips: ["Open the post, tap Share, then Copy link", "Posts from protected accounts won't work"]),
    Site(id: "bluesky", name: "Bluesky", hex: 0x1185FE, subtitle: "Videos in posts",
         placeholder: "https://bsky.app/profile/…/post/…", hosts: ["bsky.app"],
         tips: ["Tap ⋯ on a post, then Copy link to post"]),
    Site(id: "linkedin", name: "LinkedIn", hex: 0x0A66C2, subtitle: "Videos in public posts",
         placeholder: "https://www.linkedin.com/posts/…", hosts: ["linkedin.com", "lnkd.in"],
         tips: ["Tap ⋯ on the post, then Copy link to post",
                "Connections-only posts and LinkedIn Learning won't work"]),
    Site(id: "reddit", name: "Reddit", hex: 0xFF4500, subtitle: "Video posts, with sound merged back in",
         placeholder: "https://www.reddit.com/r/…/comments/…", hosts: ["reddit.com", "redd.it"],
         tips: ["Tap Share, then Copy link", "Sound is stored separately; the server joins it back"]),
    Site(id: "pinterest", name: "Pinterest", hex: 0xE60023, subtitle: "Video pins and Idea pins",
         placeholder: "https://www.pinterest.com/pin/…",
         hosts: ["pinterest.com", "pin.it", "pinterest.co.uk", "pinterest.ca", "pinterest.de", "pinterest.fr",
                 "pinterest.es", "pinterest.it", "pinterest.com.au", "pinterest.jp", "pinterest.co.in"],
         tips: ["Tap Share, then Copy link", "pin.it short links work", "Image-only pins have no video"]),
    Site(id: "youtube", name: "YouTube", hex: 0xE62117, subtitle: "Videos and Shorts, up to 4K/8K",
         placeholder: "https://www.youtube.com/watch?v=…", hosts: ["youtube.com", "youtu.be"],
         tips: ["Tap Share, then Copy link", "A video inside a playlist downloads just that video",
                "Members-only and private videos won't work"]),
    Site(id: "whatsapp", name: "WhatsApp", hex: 0x25D366, subtitle: "WhatsApp videos have no public link",
         placeholder: "", hosts: ["whatsapp.com", "wa.me"], tips: []),
]

func siteByID(_ id: String) -> Site { sites.first { $0.id == id } ?? sites[0] }

func siteForURL(_ text: String) -> Site? {
    guard let host = URL(string: text.trimmingCharacters(in: .whitespacesAndNewlines))?.host?.lowercased() else { return nil }
    return sites.first { s in s.hosts.contains { host == $0 || host.hasSuffix("." + $0) } }
}

func firstLink(in text: String?) -> String? {
    guard let text, let range = text.range(of: #"https?://\S+"#, options: .regularExpression) else { return nil }
    return String(text[range]).trimmingCharacters(in: CharacterSet(charactersIn: ".,)\"'"))
}
