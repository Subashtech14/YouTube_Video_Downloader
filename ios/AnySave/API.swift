import Foundation

struct APIError: LocalizedError {
    let message: String
    var errorDescription: String? { message }
}

struct Quality: Decodable, Hashable {
    let height: Int
    let label: String
    let tag: String?
    let hdr: Bool?
    let hasH264: Bool?
    let size: [String: Double?]?
}

struct VideoInfo: Decodable {
    let title: String?
    let site: String?
    let uploader: String?
    let duration: Double?
    let thumbnail: String?
    let qualities: [Quality]
}

struct Job: Decodable, Identifiable, Hashable {
    let id: String
    let title: String?
    let thumbnail: String?
    let label: String?
    let status: String
    let message: String?
    let done: Double?
    let total: Double?
    let speed: Double?
    let eta: Double?
    let postProgress: Double?
    let filename: String?
    let error: String?

    var isActive: Bool { ["queued", "preparing", "downloading", "processing"].contains(status) }
    var progress: Double? {
        if status == "done" { return 1 }
        if status == "processing", let p = postProgress { return p / 100 }
        if let t = total, t > 0 { return min(1, (done ?? 0) / t) }
        return nil
    }
}

struct DownloadRequest: Encodable {
    let url: String
    let mode: String
    let height: Int?
    let compat: Bool
    let audioFormat: String
    let audioBitrate: String
    let connections: Int
    let title: String?
    let thumbnail: String?
}

struct AnySaveAPI {
    let base: String
    let token: String

    init(server: String, token: String) {
        var b = server.trimmingCharacters(in: .whitespacesAndNewlines)
        while b.hasSuffix("/") { b.removeLast() }
        if !b.hasPrefix("http://") && !b.hasPrefix("https://") { b = "http://" + b }
        base = b
        self.token = token.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private static let decoder: JSONDecoder = {
        let d = JSONDecoder(); d.keyDecodingStrategy = .convertFromSnakeCase; return d
    }()
    private static let encoder: JSONEncoder = {
        let e = JSONEncoder(); e.keyEncodingStrategy = .convertToSnakeCase; return e
    }()

    func request(_ path: String, method: String = "GET") throws -> URLRequest {
        guard let url = URL(string: base + path) else { throw APIError(message: "The server address isn't valid.") }
        var r = URLRequest(url: url, timeoutInterval: 120)
        r.httpMethod = method
        r.setValue(token, forHTTPHeaderField: "X-AnySave-Token")
        return r
    }

    private func send(_ path: String, method: String = "GET", body: Data? = nil) async throws -> Data {
        var r = try request(path, method: method)
        if let body {
            r.httpBody = body
            r.setValue("application/json", forHTTPHeaderField: "Content-Type")
        }
        let data: Data, response: URLResponse
        do {
            (data, response) = try await URLSession.shared.data(for: r)
        } catch {
            throw APIError(message: "Can't reach the AnySave server at \(base). Is it running with ./start.sh --lan, and is this iPhone on the same Wi-Fi?")
        }
        let code = (response as? HTTPURLResponse)?.statusCode ?? 0
        guard (200..<300).contains(code) else {
            let msg = (try? JSONSerialization.jsonObject(with: data) as? [String: Any])?["error"] as? String
            throw APIError(message: msg ?? "Server error (\(code))")
        }
        return data
    }

    func status() async throws -> [String: Any] {
        (try JSONSerialization.jsonObject(with: await send("/api/status")) as? [String: Any]) ?? [:]
    }

    func info(url: String) async throws -> VideoInfo {
        let body = try JSONSerialization.data(withJSONObject: ["url": url])
        return try Self.decoder.decode(VideoInfo.self, from: await send("/api/info", method: "POST", body: body))
    }

    func download(_ req: DownloadRequest) async throws -> Job {
        try Self.decoder.decode(Job.self, from: await send("/api/download", method: "POST", body: Self.encoder.encode(req)))
    }

    func jobs() async throws -> [Job] {
        try Self.decoder.decode([Job].self, from: await send("/api/jobs"))
    }

    func cancel(_ id: String) async throws { _ = try await send("/api/jobs/\(id)/cancel", method: "POST") }
    func remove(_ id: String) async throws { _ = try await send("/api/jobs/\(id)", method: "DELETE") }
    func clearFinished() async throws { _ = try await send("/api/jobs/clear", method: "POST") }

    func fetchFile(_ job: Job) async throws -> URL {
        let (tmp, response) = try await URLSession.shared.download(for: request("/api/jobs/\(job.id)/file"))
        guard (response as? HTTPURLResponse)?.statusCode == 200 else { throw APIError(message: "The file isn't ready on the computer.") }
        let dir = FileManager.default.urls(for: .documentDirectory, in: .userDomainMask)[0].appendingPathComponent("AnySave", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        let name = (job.filename ?? "\(job.title ?? "video").mp4").replacingOccurrences(of: "/", with: "_")
        let dest = dir.appendingPathComponent(name)
        try? FileManager.default.removeItem(at: dest)
        try FileManager.default.moveItem(at: tmp, to: dest)
        return dest
    }
}

func formatBytes(_ n: Double?) -> String {
    guard let n, n > 0 else { return "0 B" }
    return ByteCountFormatter.string(fromByteCount: Int64(n), countStyle: .file)
}

func formatDuration(_ s: Double?) -> String {
    guard let s, s > 0 else { return "" }
    let t = Int(s), h = t / 3600, m = t % 3600 / 60, x = t % 60
    return h > 0 ? String(format: "%d:%02d:%02d", h, m, x) : String(format: "%d:%02d", m, x)
}
