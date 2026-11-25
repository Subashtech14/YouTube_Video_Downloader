import Photos
import SwiftUI

@MainActor
final class AppModel: ObservableObject {
    private let defaults = UserDefaults.standard
    @Published var server: String { didSet { defaults.set(server, forKey: "server") } }
    @Published var token: String { didSet { defaults.set(token, forKey: "token") } }
    @Published var autoSave: Bool { didSet { defaults.set(autoSave, forKey: "autoSave") } }
    @Published var connections: Int { didSet { defaults.set(connections, forKey: "connections") } }
    var configured: Bool { !server.trimmingCharacters(in: .whitespaces).isEmpty }

    @Published var tab: Site = sites[0]
    @Published var url = ""
    @Published var mismatch: Site?
    @Published var loading = false
    @Published var error: String?
    @Published var info: VideoInfo?
    @Published var mode = "both"
    @Published var height: Int?
    @Published var compat = true
    @Published var audioFormat = "mp3"
    @Published var audioBitrate = "192"
    @Published var toast: String?

    @Published var jobs: [Job] = []
    @Published var savedFiles: [String: URL] = [:]
    private var mine = Set(UserDefaults.standard.stringArray(forKey: "mine") ?? [])
    private var saving = Set<String>()
    private var infoURL = ""
    private var poller: Task<Void, Never>?

    init() {
        let d = UserDefaults.standard
        server = d.string(forKey: "server") ?? ""
        token = d.string(forKey: "token") ?? ""
        autoSave = d.object(forKey: "autoSave") as? Bool ?? true
        connections = d.object(forKey: "connections") as? Int ?? 16
        tab = siteByID(d.string(forKey: "tab") ?? "all")
    }

    private var api: AnySaveAPI { AnySaveAPI(server: server, token: token) }

    func testConnection(server: String, token: String) async -> String {
        do {
            let s = try await AnySaveAPI(server: server, token: token).status()
            if s["download_dir"] == nil { return "That address answered, but it isn't AnySave." }
            return (s["ffmpeg"] as? Bool) == false
                ? "Connected. ffmpeg is missing on the computer, so quality is limited." : "Connected."
        } catch {
            return error.localizedDescription
        }
    }


    func select(_ s: Site, clear: Bool) {
        tab = s; defaults.set(s.id, forKey: "tab"); mismatch = nil
        if clear { url = ""; info = nil; error = nil }
    }

    @discardableResult
    func checkMatch() -> Bool {
        mismatch = nil
        guard let found = siteForURL(url), found.id != tab.id else { return true }
        if tab.id == "all" { select(found, clear: false); return true }
        mismatch = found
        return false
    }

    func switchToMismatch() {
        guard let m = mismatch else { return }
        select(m, clear: false)
        lookup()
    }

    func handleOpenURL(_ incoming: URL) {
        let comps = URLComponents(url: incoming, resolvingAgainstBaseURL: false)
        let link = comps?.queryItems?.first { $0.name == "url" }?.value ?? firstLink(in: incoming.absoluteString.removingPercentEncoding)
        receive(link)
    }

    func receive(_ text: String?) {
        guard let link = firstLink(in: text) else { toast = "No link found"; return }
        select(siteForURL(link) ?? sites[0], clear: true)
        url = link
        if configured { lookup() }
    }


    func lookup() {
        let u = url.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !u.isEmpty, !loading else { return }
        guard u.hasPrefix("http://") || u.hasPrefix("https://") else { error = "That doesn't look like a link."; return }
        guard checkMatch() else { return }
        guard configured else { error = "Set up your AnySave server first (tap the gear icon)."; return }
        loading = true; error = nil; info = nil
        Task {
            defer { loading = false }
            do {
                let i = try await api.info(url: u)
                info = i; infoURL = u
                height = i.qualities.first { $0.height <= 1080 }?.height ?? i.qualities.first?.height
            } catch {
                self.error = error.localizedDescription
            }
        }
    }

    func size(for q: Quality) -> Double? {
        let key = (mode == "video" ? "video" : "both") + (compat ? "_compat" : "")
        let camel = key.replacingOccurrences(of: "_compat", with: "Compat")
        return (q.size?[key] ?? q.size?[camel]) ?? nil
    }

    func startDownload() {
        guard let i = info else { return }
        let req = DownloadRequest(url: infoURL, mode: mode, height: mode == "audio" ? nil : height, compat: compat,
                                  audioFormat: audioFormat, audioBitrate: audioBitrate, connections: connections,
                                  title: i.title, thumbnail: i.thumbnail)
        Task {
            do {
                let job = try await api.download(req)
                mine.insert(job.id)
                UserDefaults.standard.set(Array(mine), forKey: "mine")
                jobs.removeAll { $0.id == job.id }
                jobs.insert(job, at: 0)
                toast = "Added to downloads"
                startPolling()
            } catch {
                self.error = error.localizedDescription
            }
        }
    }


    func startPolling() {
        guard configured else { return }
        poller?.cancel()
        poller = Task {
            var idle = 0
            while !Task.isCancelled {
                if let list = try? await api.jobs() {
                    jobs = list
                    if autoSave {
                        for j in list where j.status == "done" && mine.contains(j.id) && savedFiles[j.id] == nil {
                            await save(j)
                        }
                    }
                }
                idle = jobs.contains { $0.isActive } ? 0 : idle + 1
                try? await Task.sleep(nanoseconds: idle == 0 ? 1_000_000_000 : 4_000_000_000)
            }
        }
    }

    func stopPolling() { poller?.cancel(); poller = nil }

    func cancel(_ j: Job) { Task { try? await api.cancel(j.id) } }
    func remove(_ j: Job) { Task { try? await api.remove(j.id); jobs.removeAll { $0.id == j.id } } }
    func clearFinished() { Task { try? await api.clearFinished(); jobs.removeAll { !$0.isActive } } }

    func save(_ j: Job) async {
        guard !saving.contains(j.id) else { return }
        saving.insert(j.id); defer { saving.remove(j.id) }
        do {
            savedFiles[j.id] = try await api.fetchFile(j)
            toast = "Saved to Files › AnySave"
        } catch {
            toast = "Couldn't save: \(error.localizedDescription)"
        }
    }

    func saveToPhotos(_ file: URL) {
        PHPhotoLibrary.requestAuthorization(for: .addOnly) { status in
            guard status == .authorized || status == .limited else {
                Task { @MainActor in self.toast = "Allow AnySave to add to Photos in Settings" }
                return
            }
            PHPhotoLibrary.shared().performChanges({
                PHAssetChangeRequest.creationRequestForAssetFromVideo(atFileURL: file)
            }) { ok, err in
                Task { @MainActor in self.toast = ok ? "Saved to Photos" : "Photos couldn't import this file (\(err?.localizedDescription ?? "unknown error"))" }
            }
        }
    }
}
